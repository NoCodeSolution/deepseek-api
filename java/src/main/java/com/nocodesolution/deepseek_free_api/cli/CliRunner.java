package com.nocodesolution.deepseek_free_api.cli;

import com.nocodesolution.deepseek_free_api.browser.BrowserAuth;
import com.nocodesolution.deepseek_free_api.browser.BrowserAuthException;
import com.nocodesolution.deepseek_free_api.browser.BrowserAuthService;
import com.nocodesolution.deepseek_free_api.services.AuthStorage;
import com.nocodesolution.deepseek_free_api.services.AuthStorageException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI-режимы: --manual, --import, --login, --connect.
 * Объекты собираются вручную, без Spring-контекста; режимы не стартуют Tomcat.
 * Окружение читается через {@link #env(String)} из реальных env-переменных.
 */
public final class CliRunner {

    private CliRunner() {
    }

    public static boolean isCliRequest(String[] args) {
        for (String arg : args) {
            if (arg.equals("--manual") || arg.equals("--login")
                    || arg.equals("--import") || arg.equals("--connect")) {
                return true;
            }
        }
        return false;
    }

    /** Выполняет CLI-режим и возвращает exit-код. */
    public static int run(String[] args) {
        List<String> list = List.of(args);
        if (list.contains("--manual")) {
            AuthStorage.printManualInstructions();
            return 0;
        }
        int importIdx = list.indexOf("--import");
        if (importIdx >= 0) {
            if (importIdx + 2 >= list.size()) {
                System.err.println("❌ --import требует 2 аргумента: <cookies.json> \"<userToken>\"");
                return 1;
            }
            return runImport(list.get(importIdx + 1), list.get(importIdx + 2));
        }
        if (list.contains("--login")) {
            return runLogin();
        }
        int connectIdx = list.indexOf("--connect");
        if (connectIdx >= 0) {
            int port = 9222;
            if (connectIdx + 1 < list.size()) {
                try {
                    port = Integer.parseInt(list.get(connectIdx + 1));
                } catch (NumberFormatException exc) {
                    System.err.println("❌ Невалидный порт для --connect: " + list.get(connectIdx + 1));
                    return 1;
                }
            }
            return runConnect(port);
        }
        return 0;
    }

    private static int runImport(String cookiesFile, String userToken) {
        try {
            defaultStorage().importCookies(cookiesFile, userToken);
        } catch (AuthStorageException | IOException exc) {
            System.err.println("❌ " + exc.getMessage());
            return 1;
        }
        System.out.println("✅ Cookies и токен импортированы!\n");
        System.out.println("Запускай: deepseek-free-api");
        return 0;
    }

    private static int runLogin() {
        BrowserAuth browser = defaultBrowser();
        String email = env("DS_EMAIL");
        String password = env("DS_PASSWORD");
        try {
            if (email != null && !email.isEmpty() && password != null && !password.isEmpty()) {
                try {
                    browser.headlessCredentialsLogin();
                    System.out.println("✅ Авторизация DeepSeek сохранена!\n");
                    return 0;
                } catch (BrowserAuthException exc) {
                    System.out.println("⚠️ Headless-логин не удался (" + exc.getMessage() + "), открываю окно...");
                } catch (Exception exc) {
                    // сырые исключения логируем по типу — не светим креды в stderr
                    System.out.println("⚠️ Headless-логин упал (" + exc.getClass().getSimpleName() + "), открываю окно...");
                }
            }
            browser.loginAndSaveAuth();
            System.out.println("✅ Авторизация DeepSeek сохранена!\n");
            return 0;
        } catch (Exception exc) {
            System.err.println("❌ " + exc.getMessage());
            return 1;
        }
    }

    private static int runConnect(int cdpPort) {
        try {
            defaultBrowser().connectToRunningChrome(cdpPort);
        } catch (Exception exc) {
            System.err.println("❌ " + exc.getMessage());
            return 1;
        }
        System.out.println("✅ Подключение готово. Запускай: deepseek-free-api");
        return 0;
    }

    static Path defaultAuthDir() {
        String fromEnv = env("DEEPSEEK_FREE_AUTH_DIR");
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return Path.of(fromEnv);
        }
        return Path.of(System.getProperty("user.home"), ".deepseek-free-api");
    }

    private static AuthStorage defaultStorage() {
        return new AuthStorage(defaultAuthDir(), BrowserAuthService.DEFAULT_BASE_URL);
    }

    private static BrowserAuth defaultBrowser() {
        AuthStorage storage = defaultStorage();
        return new BrowserAuthService(
                BrowserAuthService.DEFAULT_BASE_URL,
                defaultAuthDir().resolve("browser-profile"),
                env("DS_EMAIL"),
                env("DS_PASSWORD"),
                headlessLoginTimeoutS(),
                storage);
    }

    private static double headlessLoginTimeoutS() {
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
