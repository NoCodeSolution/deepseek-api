package com.nocodesolution.deepseek_free_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.nocodesolution.deepseek_free_api.services.DeepSeekException.AuthRequiredException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

/**
 * Порт tests/test_server.py (дефолтный deepseek.response-format=text).
 * SSE-чанки сериализуются Jackson без пробелов: "ключ":"значение".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiControllerTest extends ApiControllerTestBase {

    // ─── базовые роуты ──────────────────────────────────────────

    @Test
    void modelsIdsExact() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/v1/models", String.class);
        assertEquals(200, response.getStatusCode().value());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("list", body.path("object").asText());
        List<String> ids = new ArrayList<>();
        body.path("data").forEach(m -> ids.add(m.path("id").asText()));
        assertEquals(List.of("deepseek-chat", "deepseek-reasoner", "deepseek-r1"), ids);
    }

    @Test
    void healthReady() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/health", String.class);
        assertEquals(200, response.getStatusCode().value());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("ok", body.path("status").asText());
        assertEquals("https://chat.deepseek.com", body.path("deepseek_url").asText());
        assertEquals("ready", body.path("state").asText());
    }

    @Test
    void authStatus200() {
        ResponseEntity<String> response = rest.getForEntity("/v1/auth/status", String.class);
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void authRefreshOk() throws Exception {
        when(authManager.refresh(false)).thenReturn(true);
        ResponseEntity<String> response = rest.postForEntity("/v1/auth/refresh", null, String.class);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("ready", objectMapper.readTree(response.getBody()).path("state").asText());
        verify(authManager).refresh(false);
    }

    @Test
    void authRefreshFail401() {
        when(authManager.refresh(false)).thenReturn(false);
        ResponseEntity<String> response = rest.postForEntity("/v1/auth/refresh", null, String.class);
        assertEquals(401, response.getStatusCode().value());
        verify(authManager).refresh(false);
    }

    @Test
    void authLoginOk() {
        when(authManager.loginNow()).thenReturn(true);
        ResponseEntity<String> response = rest.postForEntity("/v1/auth/login", null, String.class);
        assertEquals(200, response.getStatusCode().value());
        verify(authManager).loginNow();
    }

    @Test
    void authLoginFail401() {
        when(authManager.loginNow()).thenReturn(false);
        ResponseEntity<String> response = rest.postForEntity("/v1/auth/login", null, String.class);
        assertEquals(401, response.getStatusCode().value());
        verify(authManager).loginNow();
    }

    // ─── chat completions ───────────────────────────────────────

    @Test
    void nonStreamPlainTextDefault() throws Exception {
        // Дефолтный формат — голый текст для TMS, без JSON-обёртки
        ResponseEntity<String> response = postChat(
                "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"q\"}]}");
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getContentType().toString().startsWith("text/plain"));
        assertEquals("Ответ", response.getBody());
        awaitDeleted("fake-session");
    }

    @Test
    void nonStreamChatModelNoThinking() {
        ResponseEntity<String> response = postChat(
                "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"q\"}]}");
        assertEquals(200, response.getStatusCode().value());
        assertFalse(fake.calls.get(0).thinkingEnabled());
    }

    @Test
    void authErrorMaps401() throws Exception {
        useClient(new FakeDeepSeekClient(new AuthRequiredException("code 40002")));
        ResponseEntity<String> response = postChat(
                "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"q\"}]}");
        assertEquals(401, response.getStatusCode().value());
        JsonNode error = objectMapper.readTree(response.getBody()).path("error");
        assertEquals("authentication_error", error.path("type").asText());
        assertTrue(error.path("message").asText().contains("--login"));
        verify(authManager).refresh(); // refresh + один повтор до первого текста
    }

    @Test
    void streamCompletion() throws Exception {
        ResponseEntity<String> response = postChat(
                "{\"model\":\"deepseek-reasoner\",\"stream\":true,"
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"q\"}]}");
        assertEquals(200, response.getStatusCode().value(), String.valueOf(response.getBody()));
        assertTrue(response.getHeaders().getContentType().toString().startsWith("text/event-stream"));
        String body = response.getBody();
        assertTrue(body.contains("\"reasoning_content\":\"Рассуждение\""), body);
        assertTrue(body.contains("\"content\":\"Ответ\""), body);
        assertTrue(body.contains("\"finish_reason\":\"stop\""), body);
        assertTrue(body.contains("data: [DONE]"), body);
        awaitDeleted("fake-session");
    }

    @Test
    void sanitizerMasksPromptSecrets() {
        // privacy.mask-secrets=true (дефолт): email в prompt уходит в DeepSeek замаскированным
        ResponseEntity<String> response = postChat(
                "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\","
                        + "\"content\":\"мой ящик test@example.com, напиши привет\"}]}");
        assertEquals(200, response.getStatusCode().value());
        String prompt = fake.calls.get(0).prompt();
        assertTrue(prompt.contains("***email***"), prompt);
        assertFalse(prompt.contains("test@example.com"), prompt);
    }

    // ─── валидация запроса ──────────────────────────────────────

    @Test
    void invalidJson400() throws Exception {
        ResponseEntity<String> response = postChat("{not json");
        assertEquals(400, response.getStatusCode().value());
        JsonNode error = objectMapper.readTree(response.getBody()).path("error");
        assertEquals("invalid_request_error", error.path("type").asText());
    }

    @Test
    void missingMessages400() throws Exception {
        ResponseEntity<String> response = postChat("{\"model\":\"deepseek-chat\"}");
        assertEquals(400, response.getStatusCode().value());
        JsonNode error = objectMapper.readTree(response.getBody()).path("error");
        assertEquals("invalid_request_error", error.path("type").asText());
    }
}
