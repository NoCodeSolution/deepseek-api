package com.nocodesolution.deepseek_free_api.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nocodesolution.deepseek_free_api.services.DeepSeekException.AuthRequiredException;
import com.nocodesolution.deepseek_free_api.services.DeepSeekException.RateLimitException;
import com.nocodesolution.deepseek_free_api.services.DeepSeekException.UpstreamException;
import com.nocodesolution.deepseek_free_api.services.SseEventParser.StreamResult;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/** Блокирующий клиент DeepSeek web API. */
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final List<Long> AUTH_ERROR_CODES = List.of(40002L, 40003L);
    private static final String CREATE_SESSION_PATH = "/api/v0/chat_session/create";
    private static final String POW_CHALLENGE_PATH = "/api/v0/chat/create_pow_challenge";
    private static final String APP_VERSION = "2.0.0";

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String completionPath;
    private final String deletePath;
    private final PowSolver powSolver;
    private final HttpHeaders baseHeaders;
    private boolean debug;

    public DeepSeekClient(
            RestTemplate restTemplate,
            String baseUrl,
            String completionPath,
            String deletePath,
            String cookieHeader,
            String token,
            PowSolver powSolver) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.completionPath = completionPath;
        this.deletePath = deletePath;
        this.powSolver = powSolver;
        this.baseHeaders = buildHeaders(baseUrl, cookieHeader, token);
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public static HttpHeaders buildHeaders(String baseUrl, String cookieHeader, String token) {
        int tzOffset = OffsetDateTime.now().getOffset().getTotalSeconds();
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);
        headers.set("Accept", "*/*");
        headers.set("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Origin", baseUrl);
        headers.set("Referer", baseUrl + "/");
        headers.set("Cookie", cookieHeader);
        headers.set("X-App-Version", APP_VERSION);
        headers.set("x-client-platform", "web");
        headers.set("x-client-version", APP_VERSION);
        headers.set("x-client-locale", "ru");
        headers.set("x-client-timezone-offset", String.valueOf(tzOffset));
        if (token != null && !token.isEmpty()) {
            headers.set("Authorization", "Bearer " + token);
        }
        return headers;
    }

    public String createSession() {
        JsonNode data = requestJson(CREATE_SESSION_PATH, MAPPER.createObjectNode());
        JsonNode session = bizData(data).path("chat_session");
        JsonNode id = session.isObject() ? session.path("id") : null;
        if (id == null || id.isMissingNode() || id.isNull() || (id.isTextual() && id.asText().isEmpty())) {
            throw new UpstreamException("Cannot read chat session id: " + abbreviate(data.toString(), 300));
        }
        return id.asText();
    }

    public String createPowHeader(String targetPath) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("target_path", targetPath);
        JsonNode data = requestJson(POW_CHALLENGE_PATH, body);
        JsonNode challenge = bizData(data).path("challenge");
        if (!challenge.isObject()) {
            throw new UpstreamException("Cannot read PoW challenge: " + abbreviate(data.toString(), 300));
        }
        Map<String, Object> challengeMap =
                MAPPER.convertValue(challenge, new TypeReference<Map<String, Object>>() {});
        long answer = powSolver.solve(challengeMap);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("algorithm", challenge.path("algorithm").asText());
        payload.put("challenge", challenge.path("challenge").asText());
        payload.put("salt", challenge.path("salt").asText());
        payload.put("answer", answer);
        payload.put("signature", challenge.path("signature").asText());
        payload.put("target_path", targetPath);
        try {
            byte[] raw = MAPPER.writeValueAsBytes(payload);
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public StreamResult complete(
            String sessionId,
            String prompt,
            String parentMessageId,
            String modelType,
            boolean thinkingEnabled,
            boolean searchEnabled,
            BiConsumer<String, String> onDelta) {
        String powHeader = createPowHeader(completionPath);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("chat_session_id", sessionId);
        if (parentMessageId != null) {
            body.put("parent_message_id", parentMessageId);
        } else {
            body.putNull("parent_message_id");
        }
        if (modelType != null) {
            body.put("model_type", modelType);
        } else {
            body.putNull("model_type");
        }
        body.put("preempt", false);
        body.put("prompt", prompt);
        body.putArray("ref_file_ids");
        body.put("thinking_enabled", thinkingEnabled);
        body.put("search_enabled", searchEnabled);

        URI uri = URI.create(baseUrl + completionPath);
        try {
            return restTemplate.execute(
                    uri,
                    HttpMethod.POST,
                    request -> {
                        request.getHeaders().addAll(baseHeaders);
                        request.getHeaders().set("X-DS-PoW-Response", powHeader);
                        request.getBody().write(MAPPER.writeValueAsBytes(body));
                    },
                    response -> extractSse(response, onDelta));
        } catch (ResourceAccessException e) {
            throw new UpstreamException("Completion request failed: " + e.getMessage(), e);
        } catch (HttpStatusCodeException e) {
            throw mapCompletionError(e.getStatusCode().value(), e.getResponseBodyAsString());
        }
    }

    public boolean deleteSession(String sessionId) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("chat_session_id", sessionId);
            requestJson(deletePath, body);
            log.info("Чат {} удалён", sessionId);
            return true;
        } catch (DeepSeekException e) {
            log.warn("Не удалось удалить чат {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    // ─── внутреннее ───────────────────────────────────────────

    private JsonNode requestJson(String path, ObjectNode body) {
        URI uri = URI.create(baseUrl + path);
        try {
            return restTemplate.execute(
                    uri,
                    HttpMethod.POST,
                    request -> {
                        request.getHeaders().addAll(baseHeaders);
                        request.getBody().write(MAPPER.writeValueAsBytes(body));
                    },
                    response -> extractJson(response, path));
        } catch (ResourceAccessException e) {
            throw new UpstreamException("Network error at " + path + ": " + e.getMessage(), e);
        } catch (HttpStatusCodeException e) {
            return handleJsonError(path, e.getStatusCode().value(), e.getResponseBodyAsString());
        }
    }

    private JsonNode extractJson(ClientHttpResponse response, String path) throws IOException {
        String text = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        JsonNode data;
        try {
            data = MAPPER.readTree(text);
        } catch (Exception e) {
            throw new UpstreamException(
                    "Expected JSON from " + path + ", got HTTP "
                            + response.getStatusCode().value() + ": " + abbreviate(text, 180));
        }
        return evaluateJson(path, response.getStatusCode().value(), data);
    }

    private JsonNode handleJsonError(String path, int status, String text) {
        JsonNode data;
        try {
            data = MAPPER.readTree(text);
        } catch (Exception e) {
            if (status == 401 || status == 403) {
                throw new AuthRequiredException("Auth required: HTTP " + status);
            }
            throw new UpstreamException(
                    "Expected JSON from " + path + ", got HTTP " + status + ": " + abbreviate(text, 180));
        }
        return evaluateJson(path, status, data);
    }

    private JsonNode evaluateJson(String path, int status, JsonNode data) {
        Long code = null;
        if (data != null && data.isObject() && data.get("code") != null && data.get("code").isNumber()) {
            code = data.get("code").longValue();
        }
        if (status == 401 || status == 403 || (code != null && AUTH_ERROR_CODES.contains(code))) {
            throw new AuthRequiredException("Auth required: code " + (code != null ? code : status));
        }
        if (status == 429) {
            throw new RateLimitException(
                    "Rate limited: HTTP 429: " + abbreviate(data == null ? "" : data.toString(), 200));
        }
        if (!(status >= 200 && status < 300) || (code != null && code != 0)) {
            String msg = data != null && data.isObject() ? data.path("msg").asText("") : "";
            throw new UpstreamException(
                    "DeepSeek API error at " + path + ": HTTP " + status + ", code " + code + ", msg " + msg);
        }
        return data != null && data.isObject() ? data : MAPPER.createObjectNode();
    }

    private StreamResult extractSse(ClientHttpResponse response, BiConsumer<String, String> onDelta)
            throws IOException {
        int status = response.getStatusCode().value();
        MediaType contentType = response.getHeaders().getContentType();
        String contentTypeString = contentType != null ? contentType.toString() : "";
        if (!(status >= 200 && status < 300) || !contentTypeString.contains("text/event-stream")) {
            String text = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
            throw mapCompletionError(status, text);
        }
        try (ClientHttpResponse stream = response) {
            return SseEventParser.streamSse(stream.getBody(), onDelta, debug);
        }
    }

    private RuntimeException mapCompletionError(int status, String text) {
        if (status == 401 || status == 403) {
            return new AuthRequiredException("Auth required during completion");
        }
        if (status == 429) {
            return new RateLimitException("Rate limited: HTTP 429: " + abbreviate(text, 200));
        }
        JsonNode parsed = null;
        try {
            parsed = MAPPER.readTree(text);
        } catch (Exception ignored) {
            // не JSON — уйдёт в общее Upstream
        }
        if (parsed != null
                && parsed.isObject()
                && parsed.get("code") != null
                && parsed.get("code").isNumber()
                && AUTH_ERROR_CODES.contains(parsed.get("code").longValue())) {
            return new AuthRequiredException("Auth required during completion");
        }
        return new UpstreamException("Completion failed: HTTP " + status + ": " + abbreviate(text, 1000));
    }

    private static JsonNode bizData(JsonNode data) {
        JsonNode wrapper = data != null && data.isObject() ? data.get("data") : null;
        JsonNode biz = wrapper != null && wrapper.isObject() ? wrapper.get("biz_data") : null;
        return biz != null && biz.isObject() ? biz : MAPPER.createObjectNode();
    }

    private static String abbreviate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max));
    }
}
