package com.nocodesolution.deepseek_free_api.services;

/** Ошибка получения или решения PoW-челленджа (порт PowError из pow_solver.py). */
public final class PowException extends RuntimeException {

    public PowException(String message) {
        super(message);
    }
}
