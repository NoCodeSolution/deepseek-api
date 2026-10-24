package com.nocodesolution.deepseek_free_api.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cors")
public record Cors(String origin) {

    public List<String> origins() {
        if (origin == null || origin.isBlank()) {
            return List.of();
        }
        return Arrays.stream(origin.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
