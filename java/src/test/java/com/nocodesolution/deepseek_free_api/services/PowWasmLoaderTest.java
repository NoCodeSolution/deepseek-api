package com.nocodesolution.deepseek_free_api.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Офлайн-тесты порта _load_wasm_bytes: кэш-хит и скачивание (MockWebServer). */
class PowWasmLoaderTest {

    @TempDir
    Path tempDir;

    private static byte[] wasmBytes() throws Exception {
        try (InputStream in = PowWasmLoaderTest.class.getResourceAsStream("/sha3_wasm_bg.wasm")) {
            return in.readAllBytes();
        }
    }

    @Test
    void cacheHitReturnsFileWithoutNetwork() throws Exception {
        byte[] expected = wasmBytes();
        Path cacheFile = tempDir.resolve("sha3_wasm_bg.wasm");
        Files.write(cacheFile, expected);

        byte[] loaded = PowWasmLoader.load(cacheFile, "http://127.0.0.1:1/unreachable");

        assertArrayEquals(expected, loaded);
    }

    @Test
    void downloadsWritesCacheAndSendsUserAgent() throws Exception {
        byte[] expected = wasmBytes();
        Path cacheFile = tempDir.resolve("sub").resolve("sha3_wasm_bg.wasm");
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            Buffer body = new Buffer();
            body.write(expected);
            server.enqueue(new MockResponse()
                    .addHeader("Content-Type", "application/wasm")
                    .setBody(body));
            String url = server.url("/sha3_wasm_bg.wasm").toString();

            byte[] loaded = PowWasmLoader.load(cacheFile, url);

            assertArrayEquals(expected, loaded);
            assertTrue(Files.exists(cacheFile), "downloaded wasm must be written to cache");
            // повторная загрузка идёт из кэша — второго запроса к серверу нет
            assertArrayEquals(expected, PowWasmLoader.load(cacheFile, url));
            assertEquals(1, server.getRequestCount());

            RecordedRequest request = server.takeRequest();
            assertEquals("/sha3_wasm_bg.wasm", request.getPath());
            assertEquals("deepseek-free-api/2.0", request.getHeader("User-Agent"));
        } finally {
            server.shutdown();
        }
    }
}
