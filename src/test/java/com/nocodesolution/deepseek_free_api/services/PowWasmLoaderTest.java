package com.nocodesolution.deepseek_free_api.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Тесты загрузки WASM из classpath и из файла. */
class PowWasmLoaderTest {

    private static byte[] wasmBytes() throws Exception {
        try (InputStream in = PowWasmLoaderTest.class.getResourceAsStream("/sha3_wasm_bg.wasm")) {
            return in.readAllBytes();
        }
    }

    @Test
    void loadsWasmFromClasspathByDefault() throws Exception {
        byte[] expected = wasmBytes();

        byte[] loaded = PowWasmLoader.load(null);

        assertArrayEquals(expected, loaded);
    }

    @Test
    void loadsWasmFromClasspathWithBlankPath() throws Exception {
        byte[] expected = wasmBytes();

        byte[] loaded = PowWasmLoader.load("   ");

        assertArrayEquals(expected, loaded);
    }

    @Test
    void loadsWasmFromFile(@TempDir Path tempDir) throws Exception {
        byte[] expected = wasmBytes();
        Path file = tempDir.resolve("sha3_wasm_bg.wasm");
        Files.write(file, expected);

        byte[] loaded = PowWasmLoader.load(file.toString());

        assertArrayEquals(expected, loaded);
    }

    @Test
    void missingFileThrows() {
        PowException ex = assertThrows(PowException.class,
                () -> PowWasmLoader.load("/nonexistent/sha3_wasm_bg.wasm"));
        assertTrue(ex.getMessage().contains("Failed to read WASM file"));
    }
}
