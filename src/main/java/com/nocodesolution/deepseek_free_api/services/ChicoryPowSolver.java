package com.nocodesolution.deepseek_free_api.services;

import com.nocodesolution.deepseek_free_api.config.Auth;
import com.nocodesolution.deepseek_free_api.config.Deepseek;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Решатель PoW-челленджа DeepSeek на Chicory в AoT-режиме.
 * WASM-байты загружаются лениво при первом solve: кэш в auth.dir
 * или скачивание по deepseek.pow-wasm-url; модуль парсится один раз.
 */
@Component
public final class ChicoryPowSolver implements PowSolver {

    public static final String SUPPORTED_ALGORITHM = "DeepSeekHashV1";

    private static final String WASM_CACHE_FILE = "sha3_wasm_bg.wasm";

    private final Deepseek deepseek;
    private final Auth auth;
    private volatile DeepSeekHashGlue glue;

    @Autowired
    public ChicoryPowSolver(Deepseek deepseek, Auth auth) {
        this.deepseek = deepseek;
        this.auth = auth;
    }

    private ChicoryPowSolver(DeepSeekHashGlue glue) {
        this.deepseek = null;
        this.auth = null;
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
                    g = new DeepSeekHashGlue(PowWasmLoader.load(
                            Path.of(auth.dir()).resolve(WASM_CACHE_FILE), deepseek.powWasmUrl()));
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
