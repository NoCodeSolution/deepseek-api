package com.nocodesolution.deepseek_free_api.config;

import com.nocodesolution.deepseek_free_api.support.DotenvLoader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Порт Python `_load_dotenv` (config.py): читает `.env` из текущей рабочей директории.
 * Ключи добавляются PropertySource'ом "dotenv" ПОСЛЕ "systemEnvironment" —
 * реальные env-переменные имеют приоритет, .env только подставляет недостающие.
 *
 * <p>Источник — {@link SystemEnvironmentPropertySource} (а не plain
 * {@code MapPropertySource}): именно она несёт маппер UPPER_SNAKE → kebab-case,
 * поэтому relaxed binding работает и для ключей из .env
 * (PROXY_API_KEY → proxy.api-key и т.д.).
 *
 * <p>Формат файла — см. {@link DotenvLoader}.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    public static final String PROPERTY_SOURCE_NAME = "dotenv";
    public static final String SYSTEM_ENVIRONMENT_NAME = "systemEnvironment";

    private static final Logger log = LoggerFactory.getLogger(DotenvEnvironmentPostProcessor.class);

    private final Path envFile;

    public DotenvEnvironmentPostProcessor() {
        this(DotenvLoader.DEFAULT_PATH);
    }

    DotenvEnvironmentPostProcessor(Path envFile) {
        this.envFile = envFile;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, String> parsed = DotenvLoader.parse(envFile);
        if (parsed.isEmpty()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>(parsed);
        MutablePropertySources sources = environment.getPropertySources();
        SystemEnvironmentPropertySource dotenv = new SystemEnvironmentPropertySource(PROPERTY_SOURCE_NAME, values);
        if (sources.contains(SYSTEM_ENVIRONMENT_NAME)) {
            sources.addAfter(SYSTEM_ENVIRONMENT_NAME, dotenv);
        } else {
            sources.addLast(dotenv);
        }
        log.debug("Загружено {} переменных из {}", values.size(), envFile);
    }
}
