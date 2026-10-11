package com.nocodesolution.deepseek_free_api.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 16 кейсов хранилища + 2 кейса формата auth.json. Всего 18. */
class AuthStorageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmpDir;

    Path authDir;
    AuthStorage storage;

    @BeforeEach
    void setUp() {
        authDir = tmpDir.resolve("auth");
        storage = new AuthStorage(authDir, "https://chat.deepseek.com");
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ─── normalize_token ────────────────────────────────────────

    @Test
    void normalizePlain() {
        assertEquals("sk-abc", AuthStorage.normalizeToken("  sk-abc  "));
        assertEquals("", AuthStorage.normalizeToken(""));
        assertEquals("", AuthStorage.normalizeToken(null));
    }

    @Test
    void normalizeJsonString() {
        assertEquals("quoted-token", AuthStorage.normalizeToken("\"quoted-token\""));
    }

    @Test
    void normalizeJsonValueObject() {
        assertEquals("inside", AuthStorage.normalizeToken("{\"value\": \"inside\", \"other\": 1}"));
    }

    @Test
    void normalizeInvalidJsonReturnsAsIs() {
        assertEquals("not-json{", AuthStorage.normalizeToken("not-json{"));
    }

    // ─── cookie_header_from_array ───────────────────────────────

    @Test
    void cookieHeaderOk() {
        String header = AuthStorage.cookieHeaderFromArray(
                json("[{\"name\": \"ds_session_id\", \"value\": \"s1\"}, {\"name\": \"a\", \"value\": \"b\"}]"));
        assertEquals("ds_session_id=s1; a=b", header);
    }

    @Test
    void cookieHeaderRequiresSessionCookie() {
        assertThrows(AuthStorageException.class,
                () -> AuthStorage.cookieHeaderFromArray(json("[{\"name\": \"other\", \"value\": \"x\"}]")));
    }

    @Test
    void cookieHeaderRequiresArray() {
        assertThrows(AuthStorageException.class,
                () -> AuthStorage.cookieHeaderFromArray(json("{\"name\": \"x\"}")));
    }

    // ─── round-trip ─────────────────────────────────────────────

    @Test
    void writeThenRead() throws Exception {
        storage.writeSavedAuth(List.of(json("{\"name\": \"ds_session_id\", \"value\": \"s1\"}")), "tok");
        Credentials saved = storage.readSavedAuth();
        assertEquals(new Credentials("ds_session_id=s1", "tok"), saved);
    }

    @Test
    void readMissingFileReturnsNull() throws Exception {
        assertNull(storage.readSavedAuth());
    }

    @Test
    void readGarbageFileRaises() throws Exception {
        Files.createDirectories(authDir);
        Files.writeString(authDir.resolve("auth.json"), "{not json", StandardCharsets.UTF_8);
        assertThrows(IOException.class, storage::readSavedAuth);
    }

    @Test
    void tokenFallbackToLegacyField() throws Exception {
        Files.createDirectories(authDir);
        Files.writeString(
                authDir.resolve("auth.json"),
                "{\"version\": 1, \"token\": \"\\\"legacy-tok\\\"\","
                        + " \"cookies\": [{\"name\": \"ds_session_id\", \"value\": \"s\"}]}",
                StandardCharsets.UTF_8);
        assertEquals("legacy-tok", storage.readSavedAuth().token());
    }

    // ─── import_cookies ─────────────────────────────────────────

    @Test
    void importCookiesHappy() throws Exception {
        Path file = tmpDir.resolve("cookies.json");
        Files.writeString(
                file,
                "[{\"name\": \"ds_session_id\", \"value\": \"s\"}, {\"name\": \"x\", \"value\": \"y\"}]",
                StandardCharsets.UTF_8);
        Credentials result = storage.importCookies(file.toString(), "tok");
        assertEquals("tok", result.token());
        assertEquals("tok", storage.readSavedAuth().token());
    }

    @Test
    void importCookiesWrappedObject() throws Exception {
        Path file = tmpDir.resolve("cookies.json");
        Files.writeString(
                file,
                "{\"cookies\": [{\"name\": \"ds_session_id\", \"value\": \"s\"}]}",
                StandardCharsets.UTF_8);
        assertEquals("ds_session_id=s", storage.importCookies(file.toString(), "tok").cookieHeader());
    }

    @Test
    void importCookiesMissingFile() {
        assertThrows(AuthStorageException.class, () -> storage.importCookies("nope.json", "tok"));
    }

    @Test
    void importCookiesNoSessionCookie() throws Exception {
        Path file = tmpDir.resolve("cookies.json");
        Files.writeString(file, "[{\"name\": \"a\", \"value\": \"b\"}]", StandardCharsets.UTF_8);
        assertThrows(AuthStorageException.class, () -> storage.importCookies(file.toString(), "tok"));
    }

    @Test
    void importCookiesEmptyToken() throws Exception {
        Path file = tmpDir.resolve("cookies.json");
        Files.writeString(file, "[{\"name\": \"ds_session_id\", \"value\": \"s\"}]", StandardCharsets.UTF_8);
        assertThrows(AuthStorageException.class, () -> storage.importCookies(file.toString(), ""));
    }

    // ─── формат файла auth.json v1 ──

    @Test
    void writeFormatMatchesV1() throws Exception {
        storage.writeSavedAuth(List.of(json("{\"name\": \"ds_session_id\", \"value\": \"сессия\"}")), "tok");
        String raw = Files.readString(authDir.resolve("auth.json"), StandardCharsets.UTF_8);
        assertTrue(raw.contains("сессия"), "не-ASCII должен писаться без экранирования");
        String normalized = raw.replaceAll("\"savedAt\": \"[^\"]+\"", "\"savedAt\": \"<TS>\"");
        String profileDir = authDir.resolve("browser-profile").toString().replace("\\", "\\\\");
        String expected = """
                {
                  "version": 1,
                  "savedAt": "<TS>",
                  "baseUrl": "https://chat.deepseek.com",
                  "profileDir": "%s",
                  "userToken": "tok",
                  "cookies": [
                    {
                      "name": "ds_session_id",
                      "value": "сессия"
                    }
                  ]
                }""".formatted(profileDir).replace("\r\n", "\n");
        assertEquals(expected, normalized);
    }

    @Test
    void writeSetsPosixPermissions600() throws Exception {
        assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX-атрибуты недоступны — best-effort пропускаем");
        storage.writeSavedAuth(List.of(json("{\"name\": \"ds_session_id\", \"value\": \"s1\"}")), "tok");
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(authDir.resolve("auth.json"));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
    }
}
