package com.nocodesolution.deepseek_free_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth")
public record Auth(
        String dir,
        boolean interactiveLogin,
        int refreshIntervalH,
        String email,
        String password,
        int headlessLoginTimeoutS) {
}
