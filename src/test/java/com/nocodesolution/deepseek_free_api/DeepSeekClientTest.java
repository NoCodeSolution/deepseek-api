package com.nocodesolution.deepseek_free_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocodesolution.deepseek_free_api.services.deepseek.DeepSeekClient;
import com.nocodesolution.deepseek_free_api.exceptions.DeepSeekException;
import com.nocodesolution.deepseek_free_api.services.deepseek.sse.SseEventParser.StreamResult;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

/** Тесты клиента на MockWebServer (11 тестов). */
class DeepSeekClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SESSION_JSON =
            "{\"code\": 0, \"data\": {\"biz_data\": {\"chat_session\": {\"id\": \"sess-1\"}}}}";

    private static final String CHALLENGE_JSON = """
            {"code": 0,
             "data": {"biz_data": {"challenge": {
                 "algorithm": "DeepSeekHashV1",
                 "challenge": "aabbcc",
                 "salt": "42",
                 "difficulty": 1,
                 "expire_at": 1893456000,
                 "signature": "sig"}}}}
            """;

    private static final String SSE_BODY =
            "data: {\"v\": \"При\"}\n\n"
                    + "data: {\"v\": \"вет\"}\n\n"
                    + "data: {\"type\": \"THINK\", \"content\": \"думаю\", \"message_id\": 9}\n\n";

    private MockWebServer server;
    private DeepSeekClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("").toString().replaceAll("/$", "");
        client = new DeepSeekClient(
                new RestTemplate(),
                baseUrl,
                "/api/v0/chat/completion",
                "/api/v0/chat_session/delete",
                "ds_session_id=s",
                "tok",
                challenge -> 12345L);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private JsonNode requestBody(RecordedRequest request) throws Exception {
        return MAPPER.readTree(request.getBody().readUtf8());
    }

    // ─── createSession ────────────────────────────────────────

    @Test
    void createSessionOk() throws Exception {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(SESSION_JSON));
        assertEquals("sess-1", client.createSession());
        RecordedRequest request = server.takeRequest();
        assertEquals("/api/v0/chat_session/create", request.getPath());
        assertEquals("{}", requestBody(request).toString());
    }

    @Test
    void createSessionHttp401() {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"code\": 40001}"));
        assertThrows(DeepSeekException.AuthRequiredException.class, () -> client.createSession());
    }

    @Test
    void createSessionCode40002() {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("{\"code\": 40002}"));
        assertThrows(DeepSeekException.AuthRequiredException.class, () -> client.createSession());
    }

    @Test
    void createSession429() {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"code\": 1}"));
        assertThrows(DeepSeekException.RateLimitException.class, () -> client.createSession());
    }

    @Test
    void createSession500() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        assertThrows(DeepSeekException.UpstreamException.class, () -> client.createSession());
    }

    @Test
    void createSessionBusinessError() {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("{\"code\": 50001, \"msg\": \"server busy\"}"));
        DeepSeekException.UpstreamException ex = assertThrows(
                DeepSeekException.UpstreamException.class, () -> client.createSession());
        assertTrue(ex.getMessage().contains("server busy"), ex.getMessage());
    }

    // ─── PoW-заголовок ────────────────────────────────────────

    @Test
    void powHeaderPayload() throws Exception {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(CHALLENGE_JSON));
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"v\": \"x\"}\n\n"));
        client.complete("s", "hi", null, null, false, false, null);
        server.takeRequest(); // create_pow_challenge
        RecordedRequest completion = server.takeRequest();
        String header = completion.getHeader("X-DS-PoW-Response");
        String decoded = new String(Base64.getDecoder().decode(header));
        JsonNode payload = MAPPER.readTree(decoded);
        assertEquals(MAPPER.readTree("""
                {"algorithm": "DeepSeekHashV1",
                 "challenge": "aabbcc",
                 "salt": "42",
                 "answer": 12345,
                 "signature": "sig",
                 "target_path": "/api/v0/chat/completion"}
                """), payload);
    }

    // ─── complete ─────────────────────────────────────────────

    @Test
    void completeCollectsTextAndReasoning() throws Exception {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(CHALLENGE_JSON));
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setBody(SSE_BODY));
        List<String[]> deltas = new ArrayList<>();
        StreamResult result = client.complete(
                "s", "hi", null, null, true, false,
                (t, r) -> deltas.add(new String[] {t, r}));
        assertEquals(9L, result.lastMessageId());
        assertEquals("Привет", result.text());
        assertEquals("думаю", result.reasoning());
        server.takeRequest(); // create_pow_challenge
        RecordedRequest completion = server.takeRequest();
        JsonNode body = requestBody(completion);
        assertTrue(body.get("thinking_enabled").asBoolean());
        assertEquals("s", body.get("chat_session_id").asText());
    }

    @Test
    void completeNonSseErrorMapped() {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(CHALLENGE_JSON));
        server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));
        assertThrows(DeepSeekException.AuthRequiredException.class,
                () -> client.complete("s", "hi", null, null, false, false, null));
    }

    @Test
    void complete429Mapped() {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(CHALLENGE_JSON));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("slow down"));
        assertThrows(DeepSeekException.RateLimitException.class,
                () -> client.complete("s", "hi", null, null, false, false, null));
    }

    // ─── deleteSession ────────────────────────────────────────

    @Test
    void deleteSessionOkAndFailureIsSwallowed() throws Exception {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("{\"code\": 0}"));
        assertTrue(client.deleteSession("sess-x"));
        RecordedRequest request = server.takeRequest();
        assertTrue(requestBody(request).toString().contains("sess-x"), requestBody(request).toString());

        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"code\": 1, \"msg\": \"nope\"}"));
        assertFalse(client.deleteSession("sess-x"));
    }
}
