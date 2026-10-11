package com.nocodesolution.deepseek_free_api.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Парсер `.env` (порт Python `_load_dotenv` из config.py).
 * Формат: строки KEY=VALUE; пустые строки и #-комментарии пропускаются;
 * значения в кавычках (" или ') раскавычиваются.
 * Отсутствующий/нечитаемый файл — пустая карта (не падаем).
 *
 * <p>Общий хелпер: используется и Spring-контекстом
 * ({@code DotenvEnvironmentPostProcessor}), и CLI-режимами ({@code CliRunner}),
 * которые выходят до старта Spring.
 */
public final class DotenvLoader {

    /** Путь по умолчанию: .env из текущей рабочей директории, как в Python. */
    public static final Path DEFAULT_PATH = Path.of(System.getProperty("user.dir"), ".env");

    private static final Logger log = LoggerFactory.getLogger(DotenvLoader.class);

    private DotenvLoader() {
    }

    /** Читает и парсит .env; порядок ключей сохраняется. */
    public static Map<String, String> parse(Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return values;
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.indexOf('=') < 0) {
                    continue;
                }
                int eq = line.indexOf('=');
                String key = line.substring(0, eq).trim();
                if (key.isEmpty()) {
                    continue;
                }
                values.put(key, unquote(line.substring(eq + 1).trim()));
            }
        } catch (IOException e) {
            log.warn("Не удалось прочитать {}: {}", file, e.getMessage());
        }
        return values;
    }

    /** Снимает одну пару обрамляющих кавычек (" или '), если они парные. */
    static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
