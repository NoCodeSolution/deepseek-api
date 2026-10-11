package com.nocodesolution.deepseek_free_api.services;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Порт _load_wasm_bytes из pow_solver.py: байты wasm читаются из кэш-файла
 * в каталоге auth.dir; если кэша нет — скачиваются по deepseek.pow-wasm-url
 * (таймаут 30 с) и best-effort пишутся в кэш.
 */
final class PowWasmLoader {

    private static final Logger log = LoggerFactory.getLogger(PowWasmLoader.class);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(30);
    private static final String USER_AGENT = "deepseek-free-api/2.0";

    private PowWasmLoader() {
    }

    static byte[] load(Path cacheFile, String url) {
        if (Files.exists(cacheFile)) {
            try {
                return Files.readAllBytes(cacheFile);
            } catch (IOException e) {
                throw new PowException("Failed to read cached PoW WASM " + cacheFile + ": " + e);
            }
        }
        byte[] data = download(url);
        try {
            Path parent = cacheFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(cacheFile, data);
        } catch (IOException e) {
            log.warn("Не удалось записать кэш PoW WASM {}: {}", cacheFile, e.toString());
        }
        return data;
    }

    private static byte[] download(String url) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(DOWNLOAD_TIMEOUT).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new PowException("Failed to download PoW WASM: HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new PowException("Failed to download PoW WASM from " + url + ": " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PowException("PoW WASM download interrupted: " + url);
        }
    }
}
