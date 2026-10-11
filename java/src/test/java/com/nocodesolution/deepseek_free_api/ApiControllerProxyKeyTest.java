package com.nocodesolution.deepseek_free_api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

/** Порт кейса test_proxy_api_key_enforced (PROXY_API_KEY задан через proxy.api-key). */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "proxy.api-key=sekret")
class ApiControllerProxyKeyTest extends ApiControllerTestBase {

    private static final String CHAT_JSON =
            "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"user\",\"content\":\"q\"}]}";

    @Test
    void missingKeyRejected() throws Exception {
        ResponseEntity<String> response = postChat(CHAT_JSON);
        assertEquals(401, response.getStatusCode().value());
        JsonNode error = objectMapper.readTree(response.getBody()).path("error");
        assertEquals("Invalid or missing API key", error.path("message").asText());
    }

    @Test
    void validKeyAccepted() {
        ResponseEntity<String> response = postChat(CHAT_JSON, "sekret");
        assertEquals(200, response.getStatusCode().value());
    }
}
