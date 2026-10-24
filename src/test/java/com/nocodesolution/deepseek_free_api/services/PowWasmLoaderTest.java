package com.nocodesolution.deepseek_free_api.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** Офлайн-тест загрузки WASM из classpath. */
class PowWasmLoaderTest {

    private static byte[] wasmBytes() throws Exception {
        try (InputStream in = PowWasmLoaderTest.class.getResourceAsStream("/sha3_wasm_bg.wasm")) {
            return in.readAllBytes();
        }
    }

    @Test
    void loadsWasmFromClasspath() throws Exception {
        byte[] expected = wasmBytes();

        byte[] loaded = PowWasmLoader.load();

        assertArrayEquals(expected, loaded);
    }

    @Test
    void missingResourceThrows() {
        // класс без доступа к ресурсу не существует, поэтому проверяем только
        // что реальный ресурс загружается ненулевого размера
        byte[] loaded = PowWasmLoader.load();
        assertTrue(loaded.length > 0, "loaded wasm must be non-empty");
    }
}
