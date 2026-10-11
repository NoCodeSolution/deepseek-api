package com.nocodesolution.deepseek_free_api.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Парсер .env: комментарии, кавычки, пустые значения, отсутствующий файл. */
class DotenvLoaderTest {

    @TempDir
    Path tmpDir;

    @Test
    void parsesCommentsQuotesAndEmptyValues() throws IOException {
        Path file = tmpDir.resolve(".env");
        Files.writeString(
                file,
                "# комментарий\n"
                        + "HOST=0.0.0.0\n"
                        + "QUOTED=\"значение\"\n"
                        + "SINGLE='одинарные'\n"
                        + "EMPTY=\n"
                        + "  SPACED  =  пробелы по краям  \n"
                        + "строка без равно\n"
                        + "=без ключа\n");

        var values = DotenvLoader.parse(file);

        assertThat(values)
                .containsEntry("HOST", "0.0.0.0")
                .containsEntry("QUOTED", "значение")
                .containsEntry("SINGLE", "одинарные")
                .containsEntry("EMPTY", "")
                .containsEntry("SPACED", "пробелы по краям")
                .hasSize(5);
    }

    @Test
    void keepsUnpairedQuotesAsIs() throws IOException {
        Path file = tmpDir.resolve(".env");
        Files.writeString(file, "A=\"незакрытая\nB=норм\"альная\"\n");

        var values = DotenvLoader.parse(file);

        assertThat(values)
                .containsEntry("A", "\"незакрытая")
                .containsEntry("B", "норм\"альная\"");
    }

    @Test
    void missingFileReturnsEmptyMap() {
        assertThat(DotenvLoader.parse(tmpDir.resolve(".env"))).isEmpty();
    }

    @Test
    void directoryInsteadOfFileReturnsEmptyMap() {
        assertThat(DotenvLoader.parse(tmpDir)).isEmpty();
    }
}
