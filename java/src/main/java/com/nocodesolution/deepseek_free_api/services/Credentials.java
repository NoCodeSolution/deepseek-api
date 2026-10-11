package com.nocodesolution.deepseek_free_api.services;

/** Пара кредов авторизации DeepSeek (порт dict {"cookieHeader", "token"}). */
public record Credentials(String cookieHeader, String token) {
}
