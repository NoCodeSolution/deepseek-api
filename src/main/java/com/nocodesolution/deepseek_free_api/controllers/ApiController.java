package com.nocodesolution.deepseek_free_api.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nocodesolution.deepseek_free_api.config.AppProperties.Deepseek;
import com.nocodesolution.deepseek_free_api.dto.ChatCompletionRequest;
import com.nocodesolution.deepseek_free_api.services.AuthManager;
import com.nocodesolution.deepseek_free_api.services.CompletionEvent;
import com.nocodesolution.deepseek_free_api.services.CompletionService;
import com.nocodesolution.deepseek_free_api.services.CompletionService.CompletionHandle;
import com.nocodesolution.deepseek_free_api.services.DeepSeekException;
import com.nocodesolution.deepseek_free_api.services.DeepSeekException.AuthRequiredException;
import com.nocodesolution.deepseek_free_api.services.DeepSeekException.RateLimitException;
import com.nocodesolution.deepseek_free_api.services.MessageConverter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Все эндпоинты прокси в одном контроллере:
 * POST /v1/chat/completions (SSE-stream и non-stream), GET /v1/models, GET /health,
 * GET /v1/auth/status, POST /v1/auth/refresh, POST /v1/auth/login.
 */
@RestController
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final AuthManager authManager;
    private final CompletionService completionService;
    private final Deepseek deepseek;
    private final ObjectMapper objectMapper;

    public ApiController(
            AuthManager authManager,
            CompletionService completionService,
            Deepseek deepseek,
            ObjectMapper objectMapper) {
        this.authManager = authManager;
        this.completionService = completionService;
        this.deepseek = deepseek;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/v1/models")
    public Map<String, Object> listModels() {
        long now = MessageConverter.nowUnix();
        List<Map<String, Object>> models = new ArrayList<>();
        for (String id : List.of("deepseek-chat", "deepseek-reasoner", "deepseek-r1")) {
            models.add(modelEntry(id, now));
        }
        String defaultModel = deepseek.defaultModel();
        boolean present = models.stream()
                .anyMatch(m -> ((String) m.get("id")).equalsIgnoreCase(defaultModel));
        if (!present) {
            models.add(modelEntry(defaultModel, now));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("object", "list");
        body.put("data", models);
        return body;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("deepseek_url", deepseek.baseUrl());
        body.putAll(authManager.statusMap());
        return body;
    }

    @GetMapping("/v1/auth/status")
    public Map<String, Object> authStatus() {
        return authManager.statusMap();
    }

    @PostMapping("/v1/auth/refresh")
    public ResponseEntity<Map<String, Object>> authRefresh() {
        boolean ok = authManager.refresh(false);
        return ResponseEntity.status(ok ? 200 : 401).body(authManager.statusMap());
    }

    @PostMapping("/v1/auth/login")
    public ResponseEntity<Map<String, Object>> authLogin() {
        boolean ok = authManager.loginNow();
        return ResponseEntity.status(ok ? 200 : 401).body(authManager.statusMap());
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<?> chatCompletions(
            @RequestBody ChatCompletionRequest req, HttpServletResponse response) throws IOException {
        if (req.messages() == null || req.messages().isEmpty()) {
            return ResponseEntity.badRequest().body(MessageConverter.errorBody(
                    "invalid_request_error", "messages: список сообщений обязателен и не должен быть пустым"));
        }
        if (req.stream()) {
            // StreamingResponseBody внутри ResponseEntity Spring 6.2 не поддерживает;
            // пишем SSE напрямую — запрос и так на виртуальном трейде, async не нужен
            streamResponse(req, response);
            return null;
        }
        return nonStreamResponse(req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ObjectNode> unreadableBody() {
        return ResponseEntity.badRequest().body(
                MessageConverter.errorBody("invalid_request_error", "Некорректный JSON в теле запроса"));
    }

    // ─── stream ─────────────────────────────────────────────────

    private void streamResponse(ChatCompletionRequest req, HttpServletResponse response) throws IOException {
        CompletionHandle handle = completionService.startCompletion(req);
        String chunkId = MessageConverter.completionId();
        long created = MessageConverter.nowUnix();
        String model = completionService.resolveModel(req.model());
        response.setStatus(200);
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        OutputStream output = response.getOutputStream();
        try {
            // первый чанк без дельты
            writeFrame(output, MessageConverter.openaiChunk(chunkId, created, model, null, null, null));
            while (true) {
                CompletionEvent event = take(handle);
                if (event == null) {
                    return; // трейд прерван
                }
                switch (event) {
                    case CompletionEvent.TextDelta delta -> writeFrame(output, MessageConverter.openaiChunk(
                            chunkId, created, model, delta.content(), null, delta.reasoning()));
                    case CompletionEvent.Done done -> {
                        writeFrame(output, MessageConverter.openaiChunk(chunkId, created, model, null, "stop", null));
                        output.write(MessageConverter.doneFrame().getBytes(StandardCharsets.UTF_8));
                        output.flush();
                        return;
                    }
                    case CompletionEvent.ErrorEvent error -> {
                        writeFrame(output, errorPayload(error.cause()));
                        return;
                    }
                }
            }
        } finally {
            // обрыв клиента (IOException при write) или конец стрима — гасим задачу
            handle.cancelIfRunning();
        }
    }

    private static void writeFrame(OutputStream output, Object payload) throws IOException {
        output.write(MessageConverter.sseFrame(payload).getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static CompletionEvent take(CompletionHandle handle) {
        try {
            return handle.events().take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ─── non-stream ─────────────────────────────────────────────

    private ResponseEntity<?> nonStreamResponse(ChatCompletionRequest req) {
        CompletionHandle handle = completionService.startCompletion(req);
        try {
            while (true) {
                CompletionEvent event = take(handle);
                if (event == null) {
                    return ResponseEntity.internalServerError().body(
                            MessageConverter.errorBody("internal_error", "Запрос прерван"));
                }
                switch (event) {
                    case CompletionEvent.TextDelta ignored -> {
                        // промежуточные дельты в non-stream не нужны
                    }
                    case CompletionEvent.Done done -> {
                        if ("text".equals(deepseek.responseFormat())) {
                            return ResponseEntity.ok()
                                    .contentType(MediaType.TEXT_PLAIN)
                                    .body(done.text());
                        }
                        return ResponseEntity.ok(MessageConverter.openaiFull(
                                MessageConverter.completionId(),
                                MessageConverter.nowUnix(),
                                completionService.resolveModel(req.model()),
                                done.text(),
                                usage(done),
                                done.reasoning()));
                    }
                    case CompletionEvent.ErrorEvent error -> {
                        return ResponseEntity.status(errorStatus(error.cause()))
                                .body(errorPayload(error.cause()));
                    }
                }
            }
        } finally {
            handle.cancelIfRunning();
        }
    }

    private ObjectNode usage(CompletionEvent.Done done) {
        ObjectNode usage = objectMapper.createObjectNode();
        int promptTokens = MessageConverter.estimateTokens(done.prompt());
        int completionTokens = MessageConverter.estimateTokens(done.text() + done.reasoning());
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", promptTokens + completionTokens);
        return usage;
    }

    // ─── ошибки ───

    private static int errorStatus(Throwable exc) {
        if (exc instanceof AuthRequiredException) {
            return 401;
        }
        if (exc instanceof RateLimitException) {
            return 429;
        }
        if (exc instanceof DeepSeekException) {
            return 502;
        }
        return 500;
    }

    private static ObjectNode errorPayload(Throwable exc) {
        if (exc instanceof AuthRequiredException) {
            return MessageConverter.errorBody(
                    "authentication_error",
                    "Сессия DeepSeek истекла: " + exc.getMessage()
                            + ". Выполни: java -jar deepseek-free-api.jar --login");
        }
        if (exc instanceof RateLimitException) {
            return MessageConverter.errorBody("rate_limit_error", String.valueOf(exc.getMessage()));
        }
        if (exc instanceof DeepSeekException) {
            return MessageConverter.errorBody("upstream_error", String.valueOf(exc.getMessage()));
        }
        log.error("Внутренняя ошибка", exc);
        return MessageConverter.errorBody("internal_error", String.valueOf(exc.getMessage()));
    }

    private static Map<String, Object> modelEntry(String id, long created) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("object", "model");
        entry.put("created", created);
        entry.put("owned_by", "deepseek");
        return entry;
    }
}
