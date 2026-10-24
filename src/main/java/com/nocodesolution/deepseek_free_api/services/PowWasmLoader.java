package com.nocodesolution.deepseek_free_api.services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.util.StringUtils;

/**
 * Загрузчик WASM-модуля PoW.
 * Если задан {@code wasmPath} — читает файл с диска;
 * иначе берёт встроенный ресурс {@code /sha3_wasm_bg.wasm} из classpath.
 */
final class PowWasmLoader {

    private static final String WASM_RESOURCE = "/sha3_wasm_bg.wasm";

    private PowWasmLoader() {
    }

    static byte[] load(String wasmPath) {
        if (StringUtils.hasText(wasmPath)) {
            return loadFromFile(Path.of(wasmPath));
        }
        return loadFromClasspath();
    }

    private static byte[] loadFromClasspath() {
        try (InputStream in = PowWasmLoader.class.getResourceAsStream(WASM_RESOURCE)) {
            if (in == null) {
                throw new PowException("WASM resource not found on classpath: " + WASM_RESOURCE);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new PowException("Failed to read WASM resource " + WASM_RESOURCE + ": " + e);
        }
    }

    private static byte[] loadFromFile(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new PowException("Failed to read WASM file " + path + ": " + e);
        }
    }
}
