package com.nocodesolution.deepseek_free_api.services;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Персистентность авторизации (порт storage.py, формат auth.json v1 без изменений). */
public class AuthStorage {

    public static final int AUTH_FORMAT_VERSION = 1;
    public static final String SESSION_COOKIE = "ds_session_id";

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

    // ─── normalize_token ────────────────────────────────────────

    public static String normalizeToken(Object input) {
        String token;
        if (input == null) {
            token = "";
        } else if (input instanceof JsonNode node) {
            // узел уже декодирован — берём значение как есть (порт str() над распарсенным JSON)
            if (node.isNull()) {
                return "";
            }
            if (node.isTextual()) {
                token = node.asText().strip();
            } else if (node.isObject() && node.path("value").isTextual()) {
                return node.path("value").asText().strip();
            } else {
                token = node.toString().strip();
            }
        } else {
            token = String.valueOf(input).strip();
        }
        if (token.isEmpty()) {
            return "";
        }
        final JsonNode parsed;
        try {
            parsed = MAPPER.readTree(token);
        } catch (Exception e) {
            return token;
        }
        if (parsed == null) {
            return token;
        }
        if (parsed.isTextual()) {
            return parsed.asText().strip();
        }
        if (parsed.isObject() && parsed.path("value").isTextual()) {
            return parsed.path("value").asText().strip();
        }
        return token;
    }

    // ─── cookie_header_from_array ───────────────────────────────

    public static String cookieHeaderFromArray(JsonNode parsed) {
        if (parsed == null || !parsed.isArray()) {
            throw new AuthStorageException("Cookie data must be a JSON array.");
        }
        List<JsonNode> usable = new ArrayList<>();
        for (JsonNode c : parsed) {
            if (isUsableCookie(c)) {
                usable.add(c);
            }
        }
        if (!containsSessionCookie(usable)) {
            throw new AuthStorageException("Cookie file does not contain ds_session_id.");
        }
        StringBuilder header = new StringBuilder();
        for (JsonNode c : usable) {
            if (header.length() > 0) {
                header.append("; ");
            }
            header.append(c.path("name").asText()).append('=').append(c.path("value").asText());
        }
        return header.toString();
    }

    private static boolean isUsableCookie(JsonNode c) {
        if (c == null || !c.isObject()) {
            return false;
        }
        JsonNode name = c.get("name");
        if (name == null || name.isNull()) {
            return false;
        }
        if ((name.isTextual() && name.asText().isEmpty())
                || (name.isArray() && name.isEmpty())
                || (name.isObject() && name.isEmpty())) {
            return false;
        }
        return c.has("value");
    }

    private static boolean containsSessionCookie(List<JsonNode> cookies) {
        for (JsonNode c : cookies) {
            if (SESSION_COOKIE.equals(c.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    // ─── read/write ─────────────────────────────────────────────

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
        String token = normalizeToken(chosen);
        JsonNode cookies = parsed.get("cookies");
        if (cookies == null || cookies.isNull()) {
            cookies = MAPPER.createArrayNode();
        }
        String cookieHeader = cookieHeaderFromArray(cookies);
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
        Files.writeString(authFile, MAPPER.writer(new PythonStylePrinter()).writeValueAsString(payload),
                StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(authFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows и пр. — best-effort (порт except OSError: pass)
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
        String token = normalizeToken(userToken);
        if (token.isEmpty()) {
            throw new AuthStorageException("Токен не может быть пустым.");
        }
        List<JsonNode> usable = new ArrayList<>();
        for (JsonNode c : cookies) {
            if (isUsableCookie(c)) {
                usable.add(c);
            }
        }
        if (!containsSessionCookie(usable)) {
            throw new AuthStorageException(
                    "В файле cookies нет ds_session_id. Убедись, что экспортировал куки с chat.deepseek.com");
        }
        String cookieHeader = cookieHeaderFromArray(toArray(usable));
        writeSavedAuth(usable, token);
        return new Credentials(cookieHeader, token);
    }

    public static ArrayNode toArray(List<JsonNode> nodes) {
        ArrayNode array = MAPPER.createArrayNode();
        nodes.forEach(array::add);
        return array;
    }

    public static void printManualInstructions() {
        System.out.println("""
                
                ══════════════════════════════════════════════════
                  Ручной экспорт сессии DeepSeek
                ══════════════════════════════════════════════════
                
                  1. Открой Chrome и зайди на https://chat.deepseek.com
                  2. Убедись что ты залогинен (должен быть интерфейс чата)
                  3. Открой DevTools (F12 или Ctrl+Shift+I)
                  4. Перейди на вкладку Application → Local Storage
                     → https://chat.deepseek.com
                  5. Найди ключ "userToken" и скопируй его значение целиком
                  6. Перейди на вкладку Application → Cookies
                     → https://chat.deepseek.com
                  7. Экспортируй все куки в файл (или скопируй вручную)
                
                  Формат cookies JSON:
                  [
                    {"name": "ds_session_id", "value": "...", "domain": "chat.deepseek.com", ...},
                    {"name": "...", "value": "...", ...}
                  ]
                
                  Сохрани файл и выполни:
                    deepseek-free-api --import cookies.json "<userToken>"
                ══════════════════════════════════════════════════
                """);
    }

    /** Как json.dumps(indent=2) в Python: 2 пробела, "key": value, массивы построчно. */
    private static final class PythonStylePrinter extends DefaultPrettyPrinter {

        PythonStylePrinter() {
            // Python json.dumps всегда пишет \n независимо от платформы
            DefaultIndenter lf = new DefaultIndenter("  ", "\n");
            indentObjectsWith(lf);
            indentArraysWith(lf);
        }

        @Override
        public PythonStylePrinter createInstance() {
            return new PythonStylePrinter();
        }

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator g) throws IOException {
            g.writeRaw(": ");
        }
    }
}
