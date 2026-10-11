package com.nocodesolution.deepseek_free_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Все настройки приложения, сгруппированные по префиксам (план §5).
 * server.* — стандартные свойства Spring Boot, здесь не дублируются.
 */
public final class AppProperties {

    private AppProperties() {
    }

    @ConfigurationProperties(prefix = "proxy")
    public record Proxy(String apiKey) {
    }

    @ConfigurationProperties(prefix = "cors")
    public record Cors(String origin) {
    }

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
            int readTimeoutMs,
            String powWasmUrl) {
    }

    @ConfigurationProperties(prefix = "auth")
    public record Auth(
            String dir,
            boolean interactiveLogin,
            int refreshIntervalH,
            String email,
            String password,
            int headlessLoginTimeoutS) {
    }

    @ConfigurationProperties(prefix = "privacy")
    public record Privacy(
            boolean maskSecrets,
            String maskPatterns) {
    }
}
