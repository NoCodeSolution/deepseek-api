package com.nocodesolution.deepseek_free_api.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nocodesolution.deepseek_free_api.dto.ChatMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** messages→prompt и сборщики OpenAI-ответов. */
public final class MessageConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageConverter() {
    }

    public static String messagesToPrompt(List<ChatMessage> messages) {
        List<String> systemParts = new ArrayList<>();
        List<String> dialogue = new ArrayList<>();
        for (ChatMessage message : messages) {
            String text = contentToText(message.content()).strip();
            if ("system".equals(message.role())) {
                if (!text.isEmpty()) {
                    systemParts.add(text);
                }
                continue;
            }
            String speaker = "assistant".equals(message.role()) ? "Assistant" : "User";
            dialogue.add(speaker + ": " + text);
        }

        List<String> blocks = new ArrayList<>();
        if (!systemParts.isEmpty()) {
            blocks.add("<system>\n" + String.join("\n\n", systemParts) + "\n</system>");
        }
        blocks.add(String.join("\n\n", dialogue) + "\n\nAssistant:");
        return String.join("\n\n", blocks);
    }

    private static String contentToText(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode part : content) {
                if (part.isObject() && "text".equals(part.path("type").asText())) {
                    parts.add(part.path("text").asText(""));
                }
            }
            return String.join("\n", parts);
        }
        return "";
    }

    public static String completionId() {
        return "chatcmpl-" + System.currentTimeMillis();
    }

    public static long nowUnix() {
        return Instant.now().getEpochSecond();
    }

    public static ObjectNode openaiChunk(
            String chunkId,
            long created,
            String model,
            String content,
            String finishReason,
            String reasoningContent) {
        ObjectNode delta = MAPPER.createObjectNode();
        if (content != null && !content.isEmpty()) {
            delta.put("content", content);
            delta.put("role", "assistant");
        }
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            delta.put("reasoning_content", reasoningContent);
        }
        ObjectNode choice = MAPPER.createObjectNode();
        choice.put("index", 0);
        choice.set("delta", delta);
        choice.putNull("logprobs");
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        } else {
            choice.putNull("finish_reason");
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", chunkId);
        root.put("object", "chat.completion.chunk");
        root.put("created", created);
        root.put("model", model);
        root.putArray("choices").add(choice);
        return root;
    }

    public static ObjectNode openaiFull(
            String fullId,
            long created,
            String model,
            String content,
            ObjectNode usage,
            String reasoningContent) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content);
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            message.put("reasoning_content", reasoningContent);
        }
        ObjectNode choice = MAPPER.createObjectNode();
        choice.put("index", 0);
        choice.set("message", message);
        choice.putNull("logprobs");
        choice.put("finish_reason", "stop");
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", fullId);
        root.put("object", "chat.completion");
        root.put("created", created);
        root.put("model", model);
        root.putArray("choices").add(choice);
        if (usage != null) {
            root.set("usage", usage);
        } else {
            ObjectNode defaultUsage = MAPPER.createObjectNode();
            defaultUsage.put("prompt_tokens", 0);
            defaultUsage.put("completion_tokens", 0);
            defaultUsage.put("total_tokens", 0);
            root.set("usage", defaultUsage);
        }
        return root;
    }

    public static String sseFrame(Object payload) {
        String body = payload instanceof String s ? s : writeJson((JsonNode) payload);
        return "data: " + body + "\n\n";
    }

    public static String doneFrame() {
        return "data: [DONE]\n\n";
    }

    public static ObjectNode errorBody(String errorType, String message) {
        ObjectNode error = MAPPER.createObjectNode();
        error.put("type", errorType);
        error.put("message", message);
        ObjectNode root = MAPPER.createObjectNode();
        root.set("error", error);
        return root;
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.codePointCount(0, text.length()) / 4);
    }

    private static String writeJson(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
