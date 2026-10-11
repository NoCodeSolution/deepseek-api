package com.nocodesolution.deepseek_free_api.browser;

/** Ошибка браузерной авторизации. Сообщения — свои, безопасны для лога. */
public class BrowserAuthException extends RuntimeException {

    public BrowserAuthException(String message) {
        super(message);
    }

    public BrowserAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
