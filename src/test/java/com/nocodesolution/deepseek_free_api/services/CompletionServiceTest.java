package com.nocodesolution.deepseek_free_api.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.TextNode;
import com.nocodesolution.deepseek_free_api.FakeDeepSeekClient;
import com.nocodesolution.deepseek_free_api.config.Deepseek;
import com.nocodesolution.deepseek_free_api.dto.ChatCompletionRequest;
import com.nocodesolution.deepseek_free_api.dto.ChatMessage;
import com.nocodesolution.deepseek_free_api.services.CompletionService.CompletionHandle;
import com.nocodesolution.deepseek_free_api.services.DeepSeekException.AuthRequiredException;
import com.nocodesolution.deepseek_free_api.services.DeepSeekException.RateLimitException;
import com.nocodesolution.deepseek_free_api.services.DeepSeekException.UpstreamException;
import com.nocodesolution.deepseek_free_api.services.SseEventParser.StreamResult;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/**
 * Unit-тесты оркестрации CompletionService:
 * retry/backoff до первого текста, отсутствие retry после первого текста,
 * удаление чата в finally. Без Spring-контекста и сети.
 */
class CompletionServiceTest {

    private static Deepseek props() {
        return props(0);
    }

    private static Deepseek props(int retryBackoffS) {
        return new Deepseek(
                "https://chat.deepseek.com", "2.0.0", "/completion", "/delete",
                true, "deepseek-chat", "text", retryBackoffS, false, 30000, 0, "");
    }

    private static ChatCompletionRequest req() {
        return new ChatCompletionRequest(
                "deepseek-chat", List.of(new ChatMessage("user", new TextNode("q"))), false, null, null);
    }

    private static CompletionService service(AuthManager authManager, Queue<DeepSeekClient> clients) {
        return new CompletionService(authManager, new PromptSanitizer(false, List.of()), props(),
                new ModelResolver(props()), clients::poll);
    }

    private static CompletionEvent awaitEvent(CompletionHandle handle) throws InterruptedException {
        CompletionEvent event = handle.events().poll(10, TimeUnit.SECONDS);
        if (event == null) {
            throw new AssertionError("Событие не получено за 10 секунд");
        }
        return event;
    }

    /** Пропускает TextDelta, ждёт Done/ErrorEvent. */
    private static CompletionEvent awaitTerminal(CompletionHandle handle) throws InterruptedException {
        while (true) {
            CompletionEvent event = awaitEvent(handle);
            if (!(event instanceof CompletionEvent.TextDelta)) {
                return event;
            }
        }
    }

    @Test
    void authErrorTriggersRefreshAndSingleRetry() throws Exception {
        AuthManager authManager = mock(AuthManager.class);
        when(authManager.refresh()).thenReturn(true);
        FakeDeepSeekClient failing = new FakeDeepSeekClient(new AuthRequiredException("code 40002"));
        FakeDeepSeekClient ok = new FakeDeepSeekClient();
        Queue<DeepSeekClient> clients = new ArrayDeque<>(List.of(failing, ok));

        CompletionHandle handle = service(authManager, clients).startCompletion(req());

        assertInstanceOf(CompletionEvent.Done.class, awaitTerminal(handle));
        handle.task().get(10, TimeUnit.SECONDS);
        verify(authManager).refresh();
        assertTrue(failing.calls.isEmpty()); // упал ещё на createSession
        assertEquals(1, ok.calls.size());
        assertEquals(List.of("fake-session"), ok.deleted);
    }

    @Test
    void rateLimitRetriesOnceAfterBackoff() throws Exception {
        AuthManager authManager = mock(AuthManager.class);
        FakeDeepSeekClient failing = new FakeDeepSeekClient(new RateLimitException("HTTP 429"));
        FakeDeepSeekClient ok = new FakeDeepSeekClient();
        Queue<DeepSeekClient> clients = new ArrayDeque<>(List.of(failing, ok));

        CompletionHandle handle = service(authManager, clients).startCompletion(req());

        assertInstanceOf(CompletionEvent.Done.class, awaitTerminal(handle));
        verify(authManager, never()).refresh();
        assertEquals(1, ok.calls.size());
    }

    @Test
    void errorAfterFirstTextNotRetried() throws Exception {
        AuthManager authManager = mock(AuthManager.class);
        AtomicInteger factoryCalls = new AtomicInteger();
        FakeDeepSeekClient flaky = new FakeDeepSeekClient() {
            @Override
            public StreamResult complete(
                    String sessionId,
                    String prompt,
                    String parentMessageId,
                    String modelType,
                    boolean thinkingEnabled,
                    boolean searchEnabled,
                    BiConsumer<String, String> onDelta) {
                onDelta.accept("часть", "");
                throw new UpstreamException("boom mid-stream");
            }
        };
        CompletionService service = new CompletionService(
                authManager, new PromptSanitizer(false, List.of()), props(), new ModelResolver(props()),
                () -> {
                    factoryCalls.incrementAndGet();
                    return flaky;
                });

        CompletionHandle handle = service.startCompletion(req());

        CompletionEvent first = awaitEvent(handle);
        assertInstanceOf(CompletionEvent.TextDelta.class, first);
        CompletionEvent second = awaitEvent(handle);
        CompletionEvent.ErrorEvent error = assertInstanceOf(CompletionEvent.ErrorEvent.class, second);
        assertInstanceOf(UpstreamException.class, error.cause());
        handle.task().get(10, TimeUnit.SECONDS);
        assertEquals(1, factoryCalls.get()); // retry после первого текста запрещён
        verify(authManager, never()).refresh();
        assertEquals(List.of("fake-session"), flaky.deleted); // чат удалён в finally
    }

    @Test
    void cancelDuringBackoffEmitsTerminalErrorEvent() throws Exception {
        // регрессия: отмена задачи в retry-backoff (Thread.sleep) проглатывала
        // InterruptedException, и контроллер висел в take() без терминального события
        AuthManager authManager = mock(AuthManager.class);
        CountDownLatch sessionAttempted = new CountDownLatch(1);
        FakeDeepSeekClient failing = new FakeDeepSeekClient(new RateLimitException("HTTP 429")) {
            @Override
            public String createSession() {
                sessionAttempted.countDown();
                return super.createSession();
            }
        };
        CompletionService service = new CompletionService(
                authManager, new PromptSanitizer(false, List.of()), props(60), new ModelResolver(props(60)), () -> failing);

        CompletionHandle handle = service.startCompletion(req());
        assertTrue(sessionAttempted.await(10, TimeUnit.SECONDS));
        handle.cancelIfRunning();

        CompletionEvent.ErrorEvent error = assertInstanceOf(CompletionEvent.ErrorEvent.class, awaitTerminal(handle));
        assertInstanceOf(InterruptedException.class, error.cause());
        assertTrue(handle.task().isDone()); // отменённая задача завершилась, shutdown не виснет
        service.shutdown();
    }

    @Test
    void sanitizerAppliedToPrompt() throws Exception {
        AuthManager authManager = mock(AuthManager.class);
        FakeDeepSeekClient ok = new FakeDeepSeekClient();
        Queue<DeepSeekClient> clients = new ArrayDeque<>(List.of(ok));
        CompletionService service = new CompletionService(
                authManager, new PromptSanitizer(true, List.of()), props(), new ModelResolver(props()), clients::poll);
        ChatCompletionRequest req = new ChatCompletionRequest(
                "deepseek-chat",
                List.of(new ChatMessage("user", new TextNode("пиши на test@example.com"))),
                false, null, null);

        CompletionHandle handle = service.startCompletion(req);

        assertInstanceOf(CompletionEvent.Done.class, awaitTerminal(handle));
        String prompt = ok.calls.get(0).prompt();
        assertTrue(prompt.contains("***email***"), prompt);
    }
}
