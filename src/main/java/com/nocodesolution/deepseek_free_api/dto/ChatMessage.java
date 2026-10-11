package com.nocodesolution.deepseek_free_api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Сообщение чата OpenAI-формата. content — произвольный JSON:
 * строка или массив частей [{"type": "text", "text": ...}];
 * лишние поля сообщения игнорируются.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatMessage(String role, JsonNode content) {
}
