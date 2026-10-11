package com.nocodesolution.deepseek_free_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocodesolution.deepseek_free_api.services.SseEventParser;
import com.nocodesolution.deepseek_free_api.services.SseEventParser.Delta;
import com.nocodesolution.deepseek_free_api.services.SseEventParser.SseEvent;
import com.nocodesolution.deepseek_free_api.services.SseEventParser.StreamResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Разбор SSE-событий (12 тестов). */
class SseEventParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Отдаёт чанки по одному — как aiter_text() в FakeResponse. */
    private static InputStream chunked(String... chunks) {
        Iterator<byte[]> it = Arrays.stream(chunks)
                .map(s -> s.getBytes(StandardCharsets.UTF_8))
                .iterator();
        return new InputStream() {
            private byte[] current;
            private int pos;

            @Override
            public int read() {
                if (current == null || pos >= current.length) {
                    if (!it.hasNext()) {
                        return -1;
                    }
                    current = it.next();
                    pos = 0;
                }
                return current[pos++] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (current == null || pos >= current.length) {
                    if (!it.hasNext()) {
                        return -1;
                    }
                    current = it.next();
                    pos = 0;
                }
                int n = Math.min(len, current.length - pos);
                System.arraycopy(current, pos, b, off, n);
                pos += n;
                return n;
            }
        };
    }

    // ─── parseSseEvent ────────────────────────────────────────

    @Test
    void parseEventMultilineData() {
        SseEvent event = SseEventParser.parseSseEvent(
                "event: message\ndata: {\"a\": 1}\ndata: {\"b\": 2}");
        assertEquals("message", event.event());
        assertEquals("{\"a\": 1}\n{\"b\": 2}", event.data());
    }

    @Test
    void parseEventCrlf() {
        SseEvent event = SseEventParser.parseSseEvent("event: x\r\ndata: y");
        assertEquals("x", event.event());
        assertEquals("y", event.data());
    }

    @Test
    void parseEventDataLstrip() {
        SseEvent event = SseEventParser.parseSseEvent("data:   spaced");
        assertEquals("spaced", event.data());
    }

    // ─── extractDeltaText ─────────────────────────────────────

    @Test
    void appendFormat() {
        Map<String, String> cache = new HashMap<>();
        Delta delta = SseEventParser.extractDeltaText(
                json("{\"p\": \"/messages/0/content\", \"o\": \"APPEND\", \"v\": \"привет\"}"),
                cache, "");
        assertEquals("привет", delta.text());
        assertEquals("", delta.reasoning());
        assertNull(delta.messageId());
    }

    @Test
    void singleVRoot() {
        Map<String, String> cache = new HashMap<>();
        Delta delta = SseEventParser.extractDeltaText(json("{\"v\": \"кусок\"}"), cache, "");
        assertEquals("кусок", delta.text());
        assertEquals("", delta.reasoning());
    }

    @Test
    void batchFormat() {
        Map<String, String> cache = new HashMap<>();
        Delta delta = SseEventParser.extractDeltaText(json("""
                {"o": "BATCH",
                 "v": [{"p": "/a/content", "o": "APPEND", "v": "раз"},
                       {"p": "/b/content", "o": "APPEND", "v": "два"}]}
                """), cache, "");
        assertEquals("раздва", delta.text());
    }

    @Test
    void responseCacheDelta() {
        Map<String, String> cache = new HashMap<>();
        Delta first = SseEventParser.extractDeltaText(
                json("{\"type\": \"RESPONSE\", \"content\": \"abcd\", \"response_message_id\": 42}"),
                cache, "");
        assertEquals("abcd", first.text());
        assertEquals(42L, first.messageId());
        Delta second = SseEventParser.extractDeltaText(
                json("{\"type\": \"RESPONSE\", \"content\": \"abcd\", \"response_message_id\": 42}"),
                cache, "");
        assertEquals("", second.text());
        Delta third = SseEventParser.extractDeltaText(
                json("{\"type\": \"RESPONSE\", \"content\": \"abcdef\", \"response_message_id\": 42}"),
                cache, "");
        assertEquals("ef", third.text());
    }

    @Test
    void thinkGoesToReasoning() {
        Map<String, String> cache = new HashMap<>();
        Delta delta = SseEventParser.extractDeltaText(
                json("{\"type\": \"THINK\", \"content\": \"думаю\", \"message_id\": 7}"), cache, "");
        assertEquals("", delta.text());
        assertEquals("думаю", delta.reasoning());
        assertEquals(7L, delta.messageId());
    }

    @Test
    void openaiChoicesFormat() {
        Map<String, String> cache = new HashMap<>();
        Delta delta = SseEventParser.extractDeltaText(
                json("{\"choices\": [{\"delta\": {\"content\": \"chunk\"}}]}"), cache, "");
        assertEquals("chunk", delta.text());
    }

    // ─── streamSse ────────────────────────────────────────────

    @Test
    void streamSseEndToEnd() throws IOException {
        List<String> chunks = List.of(
                "data: {\"p\": \"/m/0/content\", \"o\": \"APPEND\", \"v\": \"При",
                "вет\", \"response_message_id\": 5}\n\n",
                "data: {\"type\": \"THINK\", \"content\": \"рассуждение\", \"message_id\": 5}\n\n",
                "data: [DONE]\n\n");
        List<String[]> deltas = new ArrayList<>();
        StreamResult result = SseEventParser.streamSse(
                chunked(chunks.toArray(String[]::new)),
                (t, r) -> deltas.add(new String[] {t, r}),
                false);
        assertEquals(5L, result.lastMessageId());
        assertEquals("Привет", result.text());
        assertEquals("рассуждение", result.reasoning());
        assertTrue(deltas.stream().anyMatch(d -> d[0].equals("Привет") && d[1].isEmpty()));
        assertTrue(deltas.stream().anyMatch(d -> d[0].isEmpty() && d[1].equals("рассуждение")));
    }

    @Test
    void streamSseEventSplitAcrossChunks() throws IOException {
        StreamResult result = SseEventParser.streamSse(
                chunked("data: {\"v\": \"раз", "бито\"}\n\n"), null, false);
        assertEquals("разбито", result.text());
    }

    @Test
    void streamSseIgnoresDoneMarkerAndBadJson() throws IOException {
        StreamResult result = SseEventParser.streamSse(
                chunked("data: {broken\n\n", "data: {\"v\": \"x\"}\n\n", "data: [DONE]\n\n"),
                null, false);
        assertEquals("x", result.text());
    }

    // ─── новый формат upstream (fragments/-1) ─────────────────

    @Test
    void newFormatReasonerFragments() throws IOException {
        List<String> chunks = List.of(
                "data: {\"v\":{\"response\":{\"response_message_id\":2,"
                        + "\"fragments\":[{\"id\":2,\"type\":\"THINK\",\"content\":\"We\"}]}}}\n\n",
                "data: {\"p\":\"response/fragments/-1/content\",\"o\":\"APPEND\","
                        + "\"v\":\" need answer exactly one word: pong.\"}\n\n",
                "data: {\"p\":\"response/fragments\",\"o\":\"APPEND\","
                        + "\"v\":[{\"id\":3,\"type\":\"RESPONSE\",\"content\":\"p\"}]}\n\n",
                "data: {\"p\":\"response/fragments/-1/content\",\"v\":\"ong\"}\n\n",
                "data: {\"v\":{\"response\":{\"response_message_id\":2,\"fragments\":["
                        + "{\"id\":2,\"type\":\"THINK\",\"content\":\"We need answer exactly one word: pong.\"},"
                        + "{\"id\":3,\"type\":\"RESPONSE\",\"content\":\"pong\"}]}}}\n\n",
                "data: {\"o\":\"BATCH\",\"v\":[{\"p\":\"response/status\",\"o\":\"SET\",\"v\":\"FINISHED\"}]}\n\n",
                "data: [DONE]\n\n");
        List<String[]> deltas = new ArrayList<>();
        StreamResult result = SseEventParser.streamSse(
                chunked(chunks.toArray(String[]::new)),
                (t, r) -> deltas.add(new String[] {t, r}),
                false);
        assertEquals(2L, result.lastMessageId());
        assertEquals("pong", result.text());
        assertEquals("We need answer exactly one word: pong.", result.reasoning());
        String textFromDeltas = deltas.stream().map(d -> d[0]).reduce("", String::concat);
        String reasoningFromDeltas = deltas.stream().map(d -> d[1]).reduce("", String::concat);
        assertEquals("pong", textFromDeltas);
        assertEquals("We need answer exactly one word: pong.", reasoningFromDeltas);
    }

    @Test
    void newFormatNonReasonerFragments() throws IOException {
        List<String> chunks = List.of(
                "data: {\"v\":{\"response\":{\"response_message_id\":9,"
                        + "\"fragments\":[{\"id\":1,\"type\":\"RESPONSE\",\"content\":\"Hel\"}]}}}\n\n",
                "data: {\"p\":\"response/fragments/-1/content\",\"o\":\"APPEND\",\"v\":\"lo\"}\n\n",
                "data: {\"p\":\"response/fragments/-1/content\",\"v\":\" world\"}\n\n",
                "data: {\"o\":\"BATCH\",\"v\":[{\"p\":\"response/status\",\"o\":\"SET\",\"v\":\"FINISHED\"}]}\n\n",
                "data: [DONE]\n\n");
        StreamResult result = SseEventParser.streamSse(
                chunked(chunks.toArray(String[]::new)), null, false);
        assertEquals(9L, result.lastMessageId());
        assertEquals("Hello world", result.text());
        assertEquals("", result.reasoning());
    }

    @Test
    void legacyAppendWithoutOField() {
        Map<String, String> cache = new HashMap<>();
        Delta delta = SseEventParser.extractDeltaText(
                json("{\"p\": \"/messages/0/content\", \"v\": \"часть\"}"), cache, "");
        assertEquals("часть", delta.text());
        assertEquals("", delta.reasoning());
    }

    @Test
    void newFormatReasonerRootVDeltas() throws IOException {
        List<String> chunks = List.of(
                "data: {\"v\":{\"response\":{\"response_message_id\":2,"
                        + "\"fragments\":[{\"id\":2,\"type\":\"THINK\",\"content\":\"We\"}]}}}\n\n",
                "data: {\"p\":\"response/fragments/-1/content\",\"o\":\"APPEND\",\"v\":\" need\"}\n\n",
                "data: {\"v\":\" answer\"}\n\n",
                "data: {\"v\":\" exactly\"}\n\n",
                "data: {\"v\":\" one\"}\n\n",
                "data: {\"v\":\" word:\"}\n\n",
                "data: {\"v\":\" pong.\"}\n\n",
                "data: {\"v\":\" Need\"}\n\n",
                "data: {\"v\":\" comply.\"}\n\n",
                "data: {\"p\":\"response/fragments/-1/elapsed_secs\",\"o\":\"SET\",\"v\":0.3}\n\n",
                "data: {\"p\":\"response/fragments\",\"o\":\"APPEND\","
                        + "\"v\":[{\"id\":3,\"type\":\"RESPONSE\",\"content\":\"p\"}]}\n\n",
                "data: {\"p\":\"response/fragments/-1/content\",\"v\":\"ong\"}\n\n",
                "data: {\"v\":{\"response\":{\"response_message_id\":2,\"fragments\":["
                        + "{\"id\":2,\"type\":\"THINK\","
                        + "\"content\":\"We need answer exactly one word: pong. Need comply.\"},"
                        + "{\"id\":3,\"type\":\"RESPONSE\",\"content\":\"pong\"}]}}}\n\n",
                "data: {\"o\":\"BATCH\",\"v\":["
                        + "{\"p\":\"response/quasi_status\",\"o\":\"SET\",\"v\":\"FINISHED\"},"
                        + "{\"p\":\"response/status\",\"o\":\"SET\",\"v\":\"FINISHED\"}]}\n\n",
                "data: [DONE]\n\n");
        List<String[]> deltas = new ArrayList<>();
        StreamResult result = SseEventParser.streamSse(
                chunked(chunks.toArray(String[]::new)),
                (t, r) -> deltas.add(new String[] {t, r}),
                false);
        assertEquals(2L, result.lastMessageId());
        assertEquals("pong", result.text());
        assertEquals("We need answer exactly one word: pong. Need comply.", result.reasoning());
        String textFromDeltas = deltas.stream().map(d -> d[0]).reduce("", String::concat);
        String reasoningFromDeltas = deltas.stream().map(d -> d[1]).reduce("", String::concat);
        assertEquals("pong", textFromDeltas);
        assertEquals("We need answer exactly one word: pong. Need comply.", reasoningFromDeltas);
    }
}
