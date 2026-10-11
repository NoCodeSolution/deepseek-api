package com.nocodesolution.deepseek_free_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

/** Порт кейсов test_server.py с RESPONSE_FORMAT=openai. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "deepseek.response-format=openai")
class ApiControllerOpenAiFormatTest extends ApiControllerTestBase {

    @Test
    void nonStreamOpenAiFormat() throws Exception {
        ResponseEntity<String> response = postChat(
                "{\"model\":\"deepseek-reasoner\",\"messages\":["
                        + "{\"role\":\"system\",\"content\":\"sys\"},"
                        + "{\"role\":\"user\",\"content\":\"вопрос\"}]}");
        assertEquals(200, response.getStatusCode().value());
        JsonNode body = objectMapper.readTree(response.getBody());
        JsonNode message = body.path("choices").get(0).path("message");
        assertEquals("Ответ", message.path("content").asText());
        assertEquals("Рассуждение", message.path("reasoning_content").asText());
        assertTrue(body.path("usage").path("total_tokens").asInt() > 0);

        FakeDeepSeekClient.Call call = fake.calls.get(0);
        assertTrue(call.thinkingEnabled()); // reasoner → thinking on
        assertTrue(call.prompt().contains("<system>"), call.prompt());
        awaitDeleted("fake-session"); // чат удалён после ответа
    }

    @Test
    void defaultModelWhenAbsent() throws Exception {
        ResponseEntity<String> response = postChat(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"q\"}]}");
        assertEquals(200, response.getStatusCode().value());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("deepseek-chat", body.path("model").asText());
        assertFalse(fake.calls.get(0).thinkingEnabled());
    }

    @Test
    void unknownModelMapsToDefault() throws Exception {
        ResponseEntity<String> response = postChat(
                "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"q\"}]}");
        assertEquals(200, response.getStatusCode().value());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("deepseek-chat", body.path("model").asText());
        assertNull(fake.calls.get(0).modelType()); // без "deepseek" в имени → дефолтная, model_type null
    }
}
