package com.nocodesolution.deepseek_free_api.services.deepseek.sse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Разбор SSE-событий и извлечение дельт текста DeepSeek. */
public final class SseEventParser {

    private static final Logger log = LoggerFactory.getLogger(SseEventParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String[] RESPONSE_TYPES = {"RESPONSE", "TEMPLATE_RESPONSE", "THINK"};
    private static final String FRAG_KEY_PREFIX = "frag|";
    private static final String LAST_FRAG_KEY = "__lastFragmentKey__";
    private static final String LAST_FRAG_TYPE = "__lastFragmentType__";

    private SseEventParser() {
    }

    public record SseEvent(String event, String data) {
    }

    public record Delta(String text, String reasoning, Long messageId) {
    }

    public record StreamResult(Long lastMessageId, String text, String reasoning) {
    }

    public static SseEvent parseSseEvent(String raw) {
        String event = "";
        StringBuilder data = new StringBuilder();
        for (String line : raw.split("\r?\n", -1)) {
            if (line.startsWith("event:")) {
                event = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                data.append((data.isEmpty()) ? "" : "\n").append(line.substring(5).stripLeading());
            }
        }
        return new SseEvent(event, data.toString());
    }

    public static Delta extractDeltaText(JsonNode node, Map<String, String> cache, String eventName) {
        Visitor visitor = new Visitor(cache);
        visitor.visit(node, "$");
        return new Delta(
                String.join("", visitor.parts),
                String.join("", visitor.reasoningParts),
                visitor.messageId);
    }

    private static final class Visitor {
        private final Map<String, String> cache;
        private Long messageId;
        private final List<String> parts = new ArrayList<>();
        private final List<String> reasoningParts = new ArrayList<>();

        private Visitor(Map<String, String> cache) {
            this.cache = cache;
        }

        private void visit(JsonNode node, String path) {
            if (node == null || node.isNull() || node.isValueNode()) {
                return;
            }

            if (node.isObject()) {
                JsonNode responseMessageId = node.get("response_message_id");
                if (responseMessageId != null && responseMessageId.isNumber()) {
                    messageId = responseMessageId.longValue();
                }
                JsonNode messageIdNode = node.get("message_id");
                if (messageIdNode != null && messageIdNode.isNumber()) {
                    messageId = messageIdNode.longValue();
                }
                JsonNode id = node.get("id");
                if (id != null && id.isNumber() && "ASSISTANT".equals(node.path("role").asText())) {
                    messageId = id.longValue();
                }

                if ("$".equals(path) && node.size() == 1 && node.get("v") != null && node.get("v").isTextual()) {
                    String piece = node.get("v").asText();
                    if ("THINK".equals(cache.get(LAST_FRAG_TYPE))) {
                        reasoningParts.add(piece);
                    } else {
                        parts.add(piece);
                    }
                    String fragKey = cache.get(LAST_FRAG_KEY);
                    if (fragKey != null) {
                        cache.put(fragKey, cache.getOrDefault(fragKey, "") + piece);
                    }
                    return;
                }

                JsonNode pNode = node.get("p");
                JsonNode oNode = node.get("o");
                JsonNode vNode = node.get("v");
                String pointer = pNode != null && pNode.isTextual() ? pNode.asText() : null;
                boolean appendOrAbsent = oNode == null || oNode.isNull() || "APPEND".equals(oNode.asText());

                if (pointer != null
                        && pointer.endsWith("/fragments/-1/content")
                        && vNode != null
                        && vNode.isTextual()
                        && appendOrAbsent) {
                    String piece = vNode.asText();
                    if ("THINK".equals(cache.get(LAST_FRAG_TYPE))) {
                        reasoningParts.add(piece);
                    } else {
                        parts.add(piece);
                    }
                    String fragKey = cache.get(LAST_FRAG_KEY);
                    if (fragKey != null) {
                        cache.put(fragKey, cache.getOrDefault(fragKey, "") + piece);
                    }
                    return;
                }

                if (pointer != null
                        && pointer.endsWith("/content")
                        && vNode != null
                        && vNode.isTextual()
                        && appendOrAbsent) {
                    parts.add(vNode.asText());
                    return;
                }

                if ("BATCH".equals(node.path("o").asText()) && node.get("v") != null && node.get("v").isArray()) {
                    int index = 0;
                    for (JsonNode item : node.get("v")) {
                        visit(item, path + ".v." + index);
                        index++;
                    }
                    return;
                }

                JsonNode content = node.get("content");
                String type = node.path("type").asText("");
                JsonNode fragmentId = node.get("id");
                if (isResponseType(type) && fragmentId != null && fragmentId.isNumber()) {
                    String key = FRAG_KEY_PREFIX + fragmentId.longValue();
                    String previous = cache.getOrDefault(key, "");
                    String current = content != null && content.isTextual() ? content.asText() : "";
                    String delta = fragmentDelta(previous, current);
                    cache.put(key, current.length() >= previous.length() ? current : previous);
                    cache.put(LAST_FRAG_KEY, key);
                    cache.put(LAST_FRAG_TYPE, type);
                    if ("THINK".equals(type)) {
                        reasoningParts.add(delta);
                    } else {
                        parts.add(delta);
                    }
                } else if (content != null && content.isTextual() && isResponseType(type)) {
                    String key = (messageId != null ? messageId : "unknown") + ":" + path + ":" + type;
                    String previous = cache.getOrDefault(key, "");
                    String current = content.asText();
                    String delta = current.startsWith(previous) ? current.substring(previous.length()) : current;
                    cache.put(key, current);
                    if ("THINK".equals(type)) {
                        reasoningParts.add(delta);
                    } else {
                        parts.add(delta);
                    }
                }

                JsonNode choices = node.get("choices");
                if (choices != null && choices.isArray() && !choices.isEmpty() && choices.get(0).isObject()) {
                    JsonNode delta = choices.get(0).get("delta");
                    if (delta != null && delta.isObject()
                            && delta.get("content") != null && delta.get("content").isTextual()) {
                        parts.add(delta.get("content").asText());
                    }
                }

                var fields = node.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if ("content".equals(field.getKey()) || "choices".equals(field.getKey())) {
                        continue;
                    }
                    visit(field.getValue(), path + "." + field.getKey());
                }
                return;
            }

            int index = 0;
            for (JsonNode item : node) {
                visit(item, path + "." + index);
                index++;
            }
        }

        private static boolean isResponseType(String type) {
            for (String candidate : RESPONSE_TYPES) {
                if (candidate.equals(type)) {
                    return true;
                }
            }
            return false;
        }

        private static String fragmentDelta(String previous, String current) {
            if (current.startsWith(previous)) {
                return current.substring(previous.length());
            }
            if (previous.startsWith(current)) {
                return "";
            }
            return current;
        }
    }

    public static StreamResult streamSse(InputStream in, BiConsumer<String, String> onDelta, boolean debug)
            throws IOException {
        Long lastAssistantMessageId = null;
        Map<String, String> fragments = new HashMap<>();
        StringBuilder fullText = new StringBuilder();
        StringBuilder fullReasoning = new StringBuilder();
        StringBuilder buffer = new StringBuilder();

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        byte[] bytes = new byte[8192];
        int n;
        while ((n = in.read(bytes)) != -1) {
            buffer.append(decodeChunk(decoder, bytes, n));
            lastAssistantMessageId = drainEvents(
                    buffer, fragments, fullText, fullReasoning, onDelta, debug, lastAssistantMessageId);
        }
        buffer.append(decodeChunk(decoder, bytes, 0));
        lastAssistantMessageId = drainEvents(
                buffer, fragments, fullText, fullReasoning, onDelta, debug, lastAssistantMessageId);
        return new StreamResult(lastAssistantMessageId, fullText.toString(), fullReasoning.toString());
    }

    private static CharBuffer decodeChunk(CharsetDecoder decoder, byte[] bytes, int len) {
        CharBuffer out = CharBuffer.allocate((int) (Math.max(len, 1) * decoder.maxCharsPerByte()) + 4);
        decoder.decode(ByteBuffer.wrap(bytes, 0, len), out, len == 0);
        if (len == 0) {
            decoder.flush(out);
        }
        out.flip();
        return out;
    }

    private static Long drainEvents(
            StringBuilder buffer,
            Map<String, String> fragments,
            StringBuilder fullText,
            StringBuilder fullReasoning,
            BiConsumer<String, String> onDelta,
            boolean debug,
            Long lastAssistantMessageId) {
        while (true) {
            int boundary = indexOfDoubleLf(buffer);
            if (boundary < 0) {
                break;
            }
            String rawEvent = buffer.substring(0, boundary);
            buffer.delete(0, boundary + 2);
            SseEvent event = parseSseEvent(rawEvent);
            if (event.data().isEmpty()) {
                continue;
            }
            if (debug) {
                String name = event.event().isEmpty() ? "message" : event.event();
                log.debug("[event] {} {}", name, abbreviate(event.data(), 500));
            }
            JsonNode parsed;
            try {
                parsed = MAPPER.readTree(event.data());
            } catch (Exception e) {
                continue;
            }
            Delta delta = extractDeltaText(parsed, fragments, event.event());
            if (delta.messageId() != null) {
                lastAssistantMessageId = delta.messageId();
            }
            if (!delta.text().isEmpty()) {
                fullText.append(delta.text());
                if (onDelta != null) {
                    onDelta.accept(delta.text(), "");
                }
            }
            if (!delta.reasoning().isEmpty()) {
                fullReasoning.append(delta.reasoning());
                if (onDelta != null) {
                    onDelta.accept("", delta.reasoning());
                }
            }
        }
        return lastAssistantMessageId;
    }

    private static int indexOfDoubleLf(StringBuilder buffer) {
        for (int i = 0; i + 1 < buffer.length(); i++) {
            if (buffer.charAt(i) == '\n' && buffer.charAt(i + 1) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
