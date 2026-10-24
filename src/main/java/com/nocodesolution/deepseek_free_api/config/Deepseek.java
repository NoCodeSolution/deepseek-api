package com.nocodesolution.deepseek_free_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "deepseek")
public record Deepseek(
        String baseUrl,
        String appVersion,
        String completionPath,
        String chatDeletePath,
        boolean deleteChat,
        String defaultModel,
        String responseFormat,
        int retryBackoffS,
        boolean logSse,
        int connectTimeoutMs,
        int readTimeoutMs) {
}
