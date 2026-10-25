package com.nocodesolution.deepseek_free_api.model;

/** Пара кредов авторизации DeepSeek. */
public record Credentials(String cookieHeader, String token) {
}
