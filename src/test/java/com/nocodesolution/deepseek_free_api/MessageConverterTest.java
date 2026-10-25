package com.nocodesolution.deepseek_free_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nocodesolution.deepseek_free_api.dto.ChatMessage;
import com.nocodesolution.deepseek_free_api.services.openai.MessageConverter;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Тесты конвертера сообщений (9 тестов). */
class MessageConverterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void systemGoesToPrefix() {
        String prompt = MessageConverter.messagesToPrompt(List.of(
                new ChatMessage("system", MAPPER.valueToTree("Будь вежлив.")),
                new ChatMessage("user", MAPPER.valueToTree("Привет")),
                new ChatMessage("assistant", MAPPER.valueToTree("Здравствуй!")),
                new ChatMessage("user", MAPPER.valueToTree("Как дела?"))));
        assertTrue(prompt.startsWith("<system>\nБудь вежлив.\n</system>"), prompt);
        assertTrue(prompt.contains("User: Привет"), prompt);
        assertTrue(prompt.contains("Assistant: Здравствуй!"), prompt);
        assertTrue(prompt.endsWith("\n\nAssistant:"), prompt);
    }

    @Test
    void noSystemMessages() {
        String prompt = MessageConverter.messagesToPrompt(
                List.of(new ChatMessage("user", MAPPER.valueToTree("hi"))));
        assertFalse(prompt.contains("<system>"), prompt);
        assertEquals("User: hi\n\nAssistant:", prompt);
    }

    @Test
    void contentArrayTakesTextParts() {
        String prompt = MessageConverter.messagesToPrompt(List.of(
                new ChatMessage("user", json(
                        """
                        [{"type": "text", "text": "часть1"},
                         {"type": "image_url", "url": "..."},
                         {"type": "text", "text": "часть2"}]
                        """))));
        assertTrue(prompt.contains("часть1\nчасть2"), prompt);
    }

    @Test
    void emptyMessages() {
        String prompt = MessageConverter.messagesToPrompt(List.of());
        assertTrue(prompt.endsWith("\n\nAssistant:"), prompt);
    }

    @Test
    void openaiChunkContentAndReasoning() {
        ObjectNode chunk = MessageConverter.openaiChunk(
                "id1", 100, "deepseek-reasoner", "abc", null, null);
        assertEquals("abc", chunk.at("/choices/0/delta/content").asText());
        assertEquals("assistant", chunk.at("/choices/0/delta/role").asText());

        ObjectNode rchunk = MessageConverter.openaiChunk(
                "id1", 100, "deepseek-reasoner", null, null, "думаю");
        assertEquals("думаю", rchunk.at("/choices/0/delta/reasoning_content").asText());
        assertFalse(rchunk.at("/choices/0/delta").has("content"));

        ObjectNode fin = MessageConverter.openaiChunk("id1", 100, "m", null, "stop", null);
        assertEquals("stop", fin.at("/choices/0/finish_reason").asText());
    }

    @Test
    void openaiFullUsageAndReasoning() {
        ObjectNode usage = MAPPER.createObjectNode();
        usage.put("prompt_tokens", 10);
        usage.put("completion_tokens", 5);
        usage.put("total_tokens", 15);
        ObjectNode full = MessageConverter.openaiFull(
                "id1", 100, "deepseek-reasoner", "ответ", usage, "рассуждение");
        assertEquals("ответ", full.at("/choices/0/message/content").asText());
        assertEquals("рассуждение", full.at("/choices/0/message/reasoning_content").asText());
        assertEquals(15, full.at("/usage/total_tokens").asInt());
    }

    @Test
    void sseFrameAndDone() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("a", 1);
        assertTrue(MessageConverter.sseFrame(payload).startsWith("data: {"));
        assertTrue(MessageConverter.sseFrame(payload).endsWith("\n\n"));
        assertEquals("data: [DONE]\n\n", MessageConverter.doneFrame());
    }

    @Test
    void errorBodyShape() {
        ObjectNode body = MessageConverter.errorBody("authentication_error", "msg");
        assertEquals(json("{\"error\": {\"type\": \"authentication_error\", \"message\": \"msg\"}}"), body);
    }

    @Test
    void estimateTokens() {
        assertEquals(0, MessageConverter.estimateTokens(""));
        assertEquals(1, MessageConverter.estimateTokens("abcd"));
        assertEquals(100, MessageConverter.estimateTokens("x".repeat(400)));
    }
}
