package com.nocodesolution.deepseek_free_api.exceptions;

/** Ошибка получения или решения PoW-челленджа. */
public final class PowException extends RuntimeException {

    public PowException(String message) {
        super(message);
    }
}
