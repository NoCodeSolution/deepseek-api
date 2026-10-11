package com.nocodesolution.deepseek_free_api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Запрос POST /v1/chat/completions; неизвестные поля игнорируются. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        boolean stream,
        Double temperature,
        @JsonProperty("max_tokens") Integer maxTokens) {
}
