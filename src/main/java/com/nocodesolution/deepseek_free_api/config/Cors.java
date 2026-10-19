package com.nocodesolution.deepseek_free_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cors")
public record Cors(String origin) {
}
