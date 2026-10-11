package com.nocodesolution.deepseek_free_api.services;

/** Базовая ошибка DeepSeek API. */
public class DeepSeekException extends RuntimeException {

    public DeepSeekException(String message) {
        super(message);
    }

    public DeepSeekException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 401/403 или бизнес-код 40002/40003 — нужна авторизация. */
    public static class AuthRequiredException extends DeepSeekException {
        public AuthRequiredException(String message) {
            super(message);
        }
    }

    /** 429 — лимит запросов DeepSeek. */
    public static class RateLimitException extends DeepSeekException {
        public RateLimitException(String message) {
            super(message);
        }
    }

    /** Прочая ошибка upstream. */
    public static class UpstreamException extends DeepSeekException {
        public UpstreamException(String message) {
            super(message);
        }

        public UpstreamException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
