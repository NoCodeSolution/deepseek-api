package com.nocodesolution.deepseek_free_api.cli;

import com.nocodesolution.deepseek_free_api.browser.BrowserAuth;
import com.nocodesolution.deepseek_free_api.browser.BrowserAuthService;
import com.nocodesolution.deepseek_free_api.services.AuthStorage;
import java.nio.file.Path;

/** Фабрика auth-зависимостей для CLI-режимов. */
final class CliAuthFactory {

    private CliAuthFactory() {
    }

    static Path defaultAuthDir() {
        String fromEnv = env("DEEPSEEK_FREE_AUTH_DIR");
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return Path.of(fromEnv);
        }
        return Path.of(System.getProperty("user.home"), ".deepseek-free-api");
    }

    static AuthStorage defaultStorage() {
        return new AuthStorage(defaultAuthDir(), BrowserAuthService.DEFAULT_BASE_URL);
    }

    static BrowserAuth defaultBrowser() {
        AuthStorage storage = defaultStorage();
        return new BrowserAuthService(
                BrowserAuthService.DEFAULT_BASE_URL,
                defaultAuthDir().resolve("browser-profile"),
                env("DS_EMAIL"),
                env("DS_PASSWORD"),
                headlessLoginTimeoutS(),
                storage);
    }

    static double headlessLoginTimeoutS() {
        String fromEnv = env("HEADLESS_LOGIN_TIMEOUT_S");
        if (fromEnv != null && !fromEnv.isEmpty()) {
            try {
                return Double.parseDouble(fromEnv);
            } catch (NumberFormatException ignored) {
                // дефолт ниже
            }
        }
        return 60;
    }

    /** Переменная окружения (CLI выходит до Spring-контекста, читаем напрямую). */
    static String env(String key) {
        return System.getenv(key);
    }
}
