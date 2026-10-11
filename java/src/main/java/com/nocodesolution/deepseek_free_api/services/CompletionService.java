package com.nocodesolution.deepseek_free_api.services;

import com.nocodesolution.deepseek_free_api.config.AppProperties.Deepseek;
import com.nocodesolution.deepseek_free_api.dto.ChatCompletionRequest;
import com.nocodesolution.deepseek_free_api.services.DeepSeekException.AuthRequiredException;
import com.nocodesolution.deepseek_free_api.services.DeepSeekException.RateLimitException;
import com.nocodesolution.deepseek_free_api.services.DeepSeekException.UpstreamException;
import com.nocodesolution.deepseek_free_api.services.SseEventParser.StreamResult;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Оркестрация completion (порт _attempt_completion/_run_completion из server.py):
 * задача на виртуальном трейде складывает события в очередь, контроллер читает.
 * Режим «1 вопрос = 1 чат»: сессия удаляется в finally (аналог asyncio.shield —
 * удаление не прерывается отменой основной задачи).
 */
public class CompletionService {

    private static final Logger log = LoggerFactory.getLogger(CompletionService.class);

    /** Дескриптор запущенного completion: очередь событий + отмена задачи. */
    public record CompletionHandle(BlockingQueue<CompletionEvent> events, Future<?> task) {

        public void cancelIfRunning() {
            if (!task.isDone()) {
                task.cancel(true);
            }
        }
    }

    private final AuthManager authManager;
    private final PromptSanitizer promptSanitizer;
    private final Deepseek deepseek;
    private final Supplier<DeepSeekClient> clientFactory;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public CompletionService(
            AuthManager authManager,
            PromptSanitizer promptSanitizer,
            Deepseek deepseek,
            Supplier<DeepSeekClient> clientFactory) {
        this.authManager = authManager;
        this.promptSanitizer = promptSanitizer;
        this.deepseek = deepseek;
        this.clientFactory = clientFactory;
    }

    /** Создаёт очередь, запускает задачу на виртуальном трейде, возвращает дескриптор. */
    public CompletionHandle startCompletion(ChatCompletionRequest req) {
        BlockingQueue<CompletionEvent> queue = new LinkedBlockingQueue<>();
        Future<?> task = executor.submit(() -> runCompletion(req, queue));
        return new CompletionHandle(queue, task);
    }

    /** Прерывает запущенные задачи и ждёт завершения executor'а (до 5 с). */
    public void shutdown() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("CompletionService: задачи не завершились за 5 секунд после shutdownNow");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Нормализация модели запроса: неизвестная → defaultModel (без thinking). */
    public String resolveModel(String model) {
        String requested = model == null ? "" : model.strip();
        if (requested.isEmpty()) {
            return deepseek.defaultModel();
        }
        String lowered = requested.toLowerCase(Locale.ROOT);
        if (lowered.equals(deepseek.defaultModel().toLowerCase(Locale.ROOT)) || lowered.contains("deepseek")) {
            return requested;
        }
        log.warn("Неизвестная модель '{}', заменяю на дефолтную '{}'", requested, deepseek.defaultModel());
        return deepseek.defaultModel();
    }

    static String resolveModelType(String model) {
        String lowered = model.toLowerCase(Locale.ROOT);
        if (lowered.contains("reasoner") || lowered.contains("r1")) {
            return "deepseek-reasoner";
        }
        return null;
    }

    private void runCompletion(ChatCompletionRequest req, BlockingQueue<CompletionEvent> queue) {
        AtomicBoolean emitted = new AtomicBoolean(false);
        try {
            try {
                attemptCompletion(req, queue, emitted);
            } catch (AuthRequiredException e) {
                if (emitted.get()) {
                    throw e;
                }
                log.warn("Auth error до первого текста: refresh + повтор");
                authManager.refresh();
                attemptCompletion(req, queue, emitted);
            } catch (RateLimitException | UpstreamException e) {
                if (emitted.get()) {
                    throw e;
                }
                log.warn("Upstream ошибка ({}), повтор через {}s", e.getMessage(), deepseek.retryBackoffS());
                Thread.sleep(deepseek.retryBackoffS() * 1000L);
                attemptCompletion(req, queue, emitted);
            }
        } catch (InterruptedException e) {
            // отмена задачи контроллером (обрыв клиента) — как CancelledError в Python;
            // терминальное событие в очередь, иначе ждущий take() в контроллере висит
            Thread.currentThread().interrupt();
            queue.offer(new CompletionEvent.ErrorEvent(e));
        } catch (Exception e) {
            queue.offer(new CompletionEvent.ErrorEvent(e));
        }
    }

    /** Одна попытка: создать чат → completion → удалить чат. */
    private void attemptCompletion(
            ChatCompletionRequest req, BlockingQueue<CompletionEvent> queue, AtomicBoolean emitted) {
        DeepSeekClient client = clientFactory.get();
        String sessionId = null;
        try {
            sessionId = client.createSession();
            String prompt = promptSanitizer.sanitize(MessageConverter.messagesToPrompt(req.messages()));
            String model = resolveModel(req.model());
            String modelType = resolveModelType(model);
            StreamResult result = client.complete(
                    sessionId,
                    prompt,
                    null,
                    modelType,
                    "deepseek-reasoner".equals(modelType),
                    false,
                    (piece, rpiece) -> {
                        emitted.set(true);
                        queue.offer(new CompletionEvent.TextDelta(piece, rpiece));
                    });
            queue.offer(new CompletionEvent.Done(result.text(), result.reasoning(), prompt));
        } finally {
            maybeDelete(client, sessionId);
        }
    }

    /**
     * Best-effort удаление чата на отдельном виртуальном трейде; текущий трейд
     * дожидается его конца даже при interrupt — аналог asyncio.shield.
     */
    private void maybeDelete(DeepSeekClient client, String sessionId) {
        if (!deepseek.deleteChat() || sessionId == null) {
            return;
        }
        Thread deleter = Thread.startVirtualThread(() -> {
            try {
                client.deleteSession(sessionId);
            } catch (Exception e) {
                log.warn("Удаление чата {} не удалось: {}", sessionId, e.getMessage());
            }
        });
        boolean interrupted = false;
        while (deleter.isAlive()) {
            try {
                deleter.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
