package com.nocodesolution.deepseek_free_api.services;

import java.io.IOException;
import java.io.InputStream;

/**
 * Загрузчик WASM-модуля PoW из classpath.
 * Файл {@code /sha3_wasm_bg.wasm} встроен в приложение;
 * внешний URL больше не используется.
 */
final class PowWasmLoader {

    private static final String WASM_RESOURCE = "/sha3_wasm_bg.wasm";

    private PowWasmLoader() {
    }

    static byte[] load() {
        try (InputStream in = PowWasmLoader.class.getResourceAsStream(WASM_RESOURCE)) {
            if (in == null) {
                throw new PowException("WASM resource not found on classpath: " + WASM_RESOURCE);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new PowException("Failed to read WASM resource " + WASM_RESOURCE + ": " + e);
        }
    }
}
