package com.nocodesolution.deepseek_free_api.services.pow;

import com.nocodesolution.deepseek_free_api.config.Deepseek;
import java.util.Map;

import com.nocodesolution.deepseek_free_api.exceptions.PowException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Решатель PoW-челленджа DeepSeek на Chicory в AoT-режиме.
 * WASM-байты загружаются лениво при первом solve: либо из файла
 * {@code deepseek.wasm-path}, либо из встроенного classpath-ресурса;
 * модуль парсится один раз.
 */
@Component
public final class ChicoryPowSolver implements PowSolver {

    public static final String SUPPORTED_ALGORITHM = "DeepSeekHashV1";

    private final Deepseek deepseek;
    private volatile DeepSeekHashGlue glue;

    @Autowired
    public ChicoryPowSolver(Deepseek deepseek) {
        this.deepseek = deepseek;
    }

    private ChicoryPowSolver(DeepSeekHashGlue glue) {
        this.deepseek = null;
        this.glue = glue;
    }

    /** Фабрика для тестов: солвер из готовых байт wasm без кэша и сети. */
    public static ChicoryPowSolver fromBytes(byte[] wasmBytes) {
        return new ChicoryPowSolver(new DeepSeekHashGlue(wasmBytes));
    }

    /** Фабрика для тестов: солвер поверх подставного glue (без wasm). */
    static ChicoryPowSolver withGlue(DeepSeekHashGlue glue) {
        return new ChicoryPowSolver(glue);
    }

    private DeepSeekHashGlue glue() {
        DeepSeekHashGlue g = glue;
        if (g == null) {
            synchronized (this) {
                g = glue;
                if (g == null) {
                    g = new DeepSeekHashGlue(PowWasmLoader.load(deepseek.wasmPath()));
                    glue = g;
                }
            }
        }
        return g;
    }

    @Override
    public long solve(Map<String, Object> challenge) {
        Object algorithm = challenge.get("algorithm");
        if (!SUPPORTED_ALGORITHM.equals(algorithm)) {
            throw new PowException("Unsupported PoW algorithm: " + algorithm);
        }

        Object expireAt = challenge.get("expire_at") != null
                ? challenge.get("expire_at")
                : challenge.get("expireAt");
        if (!(expireAt instanceof Number)) {
            throw new PowException("PoW challenge is missing expire_at.");
        }

        Object difficulty = challenge.get("difficulty");
        if (!(difficulty instanceof Number)) {
            throw new PowException("PoW challenge is missing difficulty.");
        }

        Double answer = glue().calculateHash(
                String.valueOf(challenge.get("challenge")),
                String.valueOf(challenge.get("salt")),
                ((Number) difficulty).doubleValue(),
                ((Number) expireAt).longValue());

        if (answer == null || answer % 1 != 0) {
            throw new PowException("PoW solver did not return a valid integer answer.");
        }
        return answer.longValue();
    }
}
