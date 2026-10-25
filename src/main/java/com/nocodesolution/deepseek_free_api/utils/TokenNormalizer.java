package com.nocodesolution.deepseek_free_api.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Нормализация токена авторизации из различных форматов хранения.
 */
public final class TokenNormalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String normalizeToken(Object input) {
        String token;
        if (input == null) {
            token = "";
        } else if (input instanceof JsonNode node) {
            // узел уже декодирован — берём значение как есть
            if (node.isNull()) {
                return "";
            }
            if (node.isTextual()) {
                token = node.asText().strip();
            } else if (node.isObject() && node.path("value").isTextual()) {
                return node.path("value").asText().strip();
            } else {
                token = node.toString().strip();
            }
        } else {
            token = String.valueOf(input).strip();
        }
        if (token.isEmpty()) {
            return "";
        }
        final JsonNode parsed;
        try {
            parsed = MAPPER.readTree(token);
        } catch (Exception e) {
            return token;
        }
        if (parsed == null) {
            return token;
        }
        if (parsed.isTextual()) {
            return parsed.asText().strip();
        }
        if (parsed.isObject() && parsed.path("value").isTextual()) {
            return parsed.path("value").asText().strip();
        }
        return token;
    }
}
