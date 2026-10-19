package com.nocodesolution.deepseek_free_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "proxy")
public record Proxy(String apiKey) {
}
