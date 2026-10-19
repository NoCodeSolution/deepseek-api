package com.nocodesolution.deepseek_free_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "privacy")
public record Privacy(
        boolean maskSecrets,
        String maskPatterns) {
}
