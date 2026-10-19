package com.nocodesolution.deepseek_free_api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.nocodesolution.deepseek_free_api.services.AuthStorageException;
import java.util.ArrayList;
import java.util.List;

/** Построение HTTP Cookie-заголовка из JSON-массива cookies. */
public final class CookieHeaderBuilder {

    public static final String SESSION_COOKIE = "ds_session_id";

    private CookieHeaderBuilder() {
    }

    public static String cookieHeaderFromArray(JsonNode parsed) {
        if (parsed == null || !parsed.isArray()) {
            throw new AuthStorageException("Cookie data must be a JSON array.");
        }
        List<JsonNode> usable = new ArrayList<>();
        for (JsonNode c : parsed) {
            if (isUsableCookie(c)) {
                usable.add(c);
            }
        }
        if (!containsSessionCookie(usable)) {
            throw new AuthStorageException("Cookie file does not contain ds_session_id.");
        }
        StringBuilder header = new StringBuilder();
        for (JsonNode c : usable) {
            if (header.length() > 0) {
                header.append("; ");
            }
            header.append(c.path("name").asText()).append('=').append(c.path("value").asText());
        }
        return header.toString();
    }

    public static boolean isUsableCookie(JsonNode c) {
        if (c == null || !c.isObject()) {
            return false;
        }
        JsonNode name = c.get("name");
        if (name == null || name.isNull()) {
            return false;
        }
        if ((name.isTextual() && name.asText().isEmpty())
                || (name.isArray() && name.isEmpty())
                || (name.isObject() && name.isEmpty())) {
            return false;
        }
        return c.has("value");
    }

    public static boolean containsSessionCookie(List<JsonNode> cookies) {
        for (JsonNode c : cookies) {
            if (SESSION_COOKIE.equals(c.path("name").asText())) {
                return true;
            }
        }
        return false;
    }
}
