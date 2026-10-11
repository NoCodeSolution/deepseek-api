package com.nocodesolution.deepseek_free_api.services;

import java.util.Map;

/**
 * Решатель PoW-челленджа DeepSeek. Реализация на Chicory — отдельная фаза;
 * контракт: challenge (biz_data.challenge из API) → числовой ответ.
 */
public interface PowSolver {

    long solve(Map<String, Object> challenge);
}
