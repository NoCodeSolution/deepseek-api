package com.nocodesolution.deepseek_free_api.services;

/** Ошибка чтения/валидации сохранённой авторизации. */
public class AuthStorageException extends RuntimeException {

    public AuthStorageException(String message) {
        super(message);
    }
}
