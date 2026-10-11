package com.nocodesolution.deepseek_free_api;

import com.nocodesolution.deepseek_free_api.services.DeepSeekClient;
import com.nocodesolution.deepseek_free_api.services.SseEventParser.StreamResult;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/** Фейк DeepSeekClient: без сети, записывает вызовы. */
public class FakeDeepSeekClient extends DeepSeekClient {

    public record Call(
            String sessionId,
            String prompt,
            String modelType,
            boolean thinkingEnabled,
            boolean searchEnabled) {
    }

    private final RuntimeException failWith;
    public final List<Call> calls = new CopyOnWriteArrayList<>();
    public final List<String> deleted = new CopyOnWriteArrayList<>();

    public FakeDeepSeekClient() {
        this(null);
    }

    public FakeDeepSeekClient(RuntimeException failWith) {
        super(null, "http://fake", "/completion", "/delete", "", "", null);
        this.failWith = failWith;
    }

    @Override
    public String createSession() {
        if (failWith != null) {
            throw failWith;
        }
        return "fake-session";
    }

    @Override
    public StreamResult complete(
            String sessionId,
            String prompt,
            String parentMessageId,
            String modelType,
            boolean thinkingEnabled,
            boolean searchEnabled,
            BiConsumer<String, String> onDelta) {
        calls.add(new Call(sessionId, prompt, modelType, thinkingEnabled, searchEnabled));
        if (failWith != null) {
            throw failWith;
        }
        if (onDelta != null) {
            onDelta.accept("Ответ", "");
            onDelta.accept("", "Рассуждение");
        }
        return new StreamResult(1L, "Ответ", "Рассуждение");
    }

    @Override
    public boolean deleteSession(String sessionId) {
        deleted.add(sessionId);
        return true;
    }
}
