package com.nocodesolution.deepseek_free_api.exceptions;

/** Ошибка браузерной авторизации. Сообщения — свои, безопасны для лога. */
public class BrowserAuthException extends RuntimeException {

    public BrowserAuthException(String message) {
        super(message);
    }

    public BrowserAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
