package com.nocodesolution.deepseek_free_api.services.auth;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nocodesolution.deepseek_free_api.auth.CookieHeaderBuilder;
import com.nocodesolution.deepseek_free_api.auth.TokenNormalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.nocodesolution.deepseek_free_api.exceptions.AuthStorageException;
import com.nocodesolution.deepseek_free_api.model.Credentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Персистентность авторизации (формат auth.json v1). */
public class AuthStorage {

    private static final Logger log = LoggerFactory.getLogger(AuthStorage.class);

    public static final int AUTH_FORMAT_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path authDir;
    private final Path authFile;
    private final Path browserProfile;
    private final String baseUrl;

    public AuthStorage(Path authDir, String baseUrl) {
        this.authDir = authDir;
        this.authFile = authDir.resolve("auth.json");
        this.browserProfile = authDir.resolve("browser-profile");
        this.baseUrl = baseUrl;
    }

    public Path authDir() {
        return authDir;
    }

    public Path authFile() {
        return authFile;
    }

    public Credentials readSavedAuth() throws IOException {
        if (!Files.exists(authFile)) {
            return null;
        }
        JsonNode parsed = MAPPER.readTree(Files.readString(authFile, StandardCharsets.UTF_8));
        if (parsed == null || !parsed.isObject()) {
            return null;
        }
        JsonNode userToken = parsed.get("userToken");
        JsonNode chosen = !falsy(userToken) ? userToken : parsed.get("token");
        String token = TokenNormalizer.normalizeToken(chosen);
        JsonNode cookies = parsed.get("cookies");
        if (cookies == null || cookies.isNull()) {
            cookies = MAPPER.createArrayNode();
        }
        String cookieHeader = CookieHeaderBuilder.cookieHeaderFromArray(cookies);
        return new Credentials(cookieHeader, token);
    }

    private static boolean falsy(JsonNode n) {
        return n == null || n.isNull()
                || (n.isTextual() && n.asText().isEmpty())
                || (n.isBoolean() && !n.asBoolean())
                || (n.isNumber() && n.doubleValue() == 0.0)
                || (n.isArray() && n.isEmpty())
                || (n.isObject() && n.isEmpty());
    }

    public void writeSavedAuth(List<JsonNode> cookies, String userToken) throws IOException {
        Files.createDirectories(authDir);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("version", AUTH_FORMAT_VERSION);
        payload.put("savedAt", Instant.now().toString());
        payload.put("baseUrl", baseUrl);
        payload.put("profileDir", browserProfile.toString());
        payload.put("userToken", userToken);
        ArrayNode array = payload.putArray("cookies");
        cookies.forEach(array::add);
        Files.writeString(authFile, MAPPER.writer(new AuthJsonPrinter()).writeValueAsString(payload),
                StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(authFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows и пр. — best-effort
        }
    }

    public Credentials importCookies(String cookiesFilePath, String userToken) throws IOException {
        Path path = Path.of(cookiesFilePath);
        if (!Files.exists(path)) {
            throw new AuthStorageException("Файл cookies не найден: " + cookiesFilePath);
        }
        final JsonNode parsed;
        try {
            parsed = MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new AuthStorageException("Файл cookies должен быть валидным JSON.");
        }
        JsonNode cookies = parsed;
        if (cookies == null || !cookies.isArray()) {
            JsonNode wrapped = cookies != null && cookies.isObject() ? cookies.get("cookies") : null;
            if (wrapped != null && wrapped.isArray()) {
                cookies = wrapped;
            } else {
                throw new AuthStorageException("Файл cookies должен быть массивом JSON-объектов.");
            }
        }
        String token = TokenNormalizer.normalizeToken(userToken);
        if (token.isEmpty()) {
            throw new AuthStorageException("Токен не может быть пустым.");
        }
        List<JsonNode> usable = new ArrayList<>();
        for (JsonNode c : cookies) {
            if (CookieHeaderBuilder.isUsableCookie(c)) {
                usable.add(c);
            }
        }
        if (!CookieHeaderBuilder.containsSessionCookie(usable)) {
            throw new AuthStorageException(
                    "В файле cookies нет ds_session_id. Убедитесь, что экспортированы куки с chat.deepseek.com");
        }
        String cookieHeader = CookieHeaderBuilder.cookieHeaderFromArray(toArray(usable));
        writeSavedAuth(usable, token);
        return new Credentials(cookieHeader, token);
    }

    public static ArrayNode toArray(List<JsonNode> nodes) {
        ArrayNode array = MAPPER.createArrayNode();
        nodes.forEach(array::add);
        return array;
    }

    public static void printManualInstructions() {
        log.info("""
                
                ==================================================
                  Ручной экспорт сессии DeepSeek
                ==================================================
                
                  1. Откройте Chrome и перейдите на https://chat.deepseek.com
                  2. Убедитесь, что выполнен вход (должен быть интерфейс чата)
                  3. Откройте DevTools (F12 или Ctrl+Shift+I)
                  4. Перейдите на вкладку Application → Local Storage
                     → https://chat.deepseek.com
                  5. Найдите ключ "userToken" и скопируйте его значение целиком
                  6. Перейдите на вкладку Application → Cookies
                     → https://chat.deepseek.com
                  7. Экспортируйте все куки в файл (или скопируйте вручную)
                
                  Формат cookies JSON:
                  [
                    {"name": "ds_session_id", "value": "...", "domain": "chat.deepseek.com", ...},
                    {"name": "...", "value": "...", ...}
                  ]
                
                  Сохраните файл и выполните:
                    deepseek-free-api --import cookies.json "<userToken>"
                ==================================================
                """);
    }

    /** Формат auth.json: 2 пробела отступ, "key": value, массивы построчно, \n-переносы. */
    private static final class AuthJsonPrinter extends DefaultPrettyPrinter {

        AuthJsonPrinter() {
            DefaultIndenter lf = new DefaultIndenter("  ", "\n");
            indentObjectsWith(lf);
            indentArraysWith(lf);
        }

        @Override
        public AuthJsonPrinter createInstance() {
            return new AuthJsonPrinter();
        }

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator g) throws IOException {
            g.writeRaw(": ");
        }
    }
}
