package com.nocodesolution.deepseek_free_api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.env.MockPropertySource;

/** Паритет tests/test_config.py (Python-версия). */
class DotenvEnvironmentPostProcessorTest {

    @TempDir
    Path tmpDir;

    private MockEnvironment process(Path envFile) {
        MockEnvironment env = new MockEnvironment();
        new DotenvEnvironmentPostProcessor(envFile).postProcessEnvironment(env, null);
        return env;
    }

    @Test
    void dotenvSetsMissingVars() throws IOException {
        Files.writeString(
                tmpDir.resolve(".env"),
                "# комментарий\nHOST=0.0.0.0\nQUOTED=\"значение\"\nSINGLE='одинарные'\nПУСТАЯ=\n");

        MockEnvironment env = process(tmpDir.resolve(".env"));

        assertThat(env.getProperty("HOST")).isEqualTo("0.0.0.0");
        assertThat(env.getProperty("QUOTED")).isEqualTo("значение");
        assertThat(env.getProperty("SINGLE")).isEqualTo("одинарные");
        assertThat(env.getProperty("ПУСТАЯ")).isEmpty();
        assertThat(env.getPropertySources().get(DotenvEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isNotNull();
    }

    @Test
    void dotenvDoesNotOverrideRealEnv() throws IOException {
        Files.writeString(tmpDir.resolve(".env"), "HOST=0.0.0.0\n");
        MockEnvironment env = new MockEnvironment();
        // имитируем реальную env-переменную: источник "systemEnvironment" с приоритетом над dotenv
        env.getPropertySources()
                .addFirst(new MockPropertySource("systemEnvironment").withProperty("HOST", "127.0.0.1"));

        new DotenvEnvironmentPostProcessor(tmpDir.resolve(".env")).postProcessEnvironment(env, null);

        assertThat(env.getProperty("HOST")).isEqualTo("127.0.0.1");
    }

    @Test
    void dotenvMissingFileOk() {
        MockEnvironment env = new MockEnvironment();

        assertThatCode(() -> new DotenvEnvironmentPostProcessor(tmpDir.resolve(".env"))
                        .postProcessEnvironment(env, null))
                .doesNotThrowAnyException();
        assertThat(env.getPropertySources().get(DotenvEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isNull();
    }

    @Test
    void dotenvKeysBindByCanonicalName() throws IOException {
        // регрессия: plain MapPropertySource ломал relaxed binding — PROXY_API_KEY из
        // .env молча игнорировался; источник должен быть SystemEnvironmentPropertySource
        Files.writeString(tmpDir.resolve(".env"), "PROXY_API_KEY=sekret\n");

        MockEnvironment env = process(tmpDir.resolve(".env"));

        assertThat(Binder.get(env).bind("proxy.api-key", Bindable.of(String.class)).orElse(null))
                .isEqualTo("sekret");
        assertThat(env.getProperty("proxy.api-key")).isEqualTo("sekret");
    }
}
