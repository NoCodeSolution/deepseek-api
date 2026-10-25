package com.nocodesolution.deepseek_free_api.services.openai;

import com.nocodesolution.deepseek_free_api.config.Deepseek;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Разрешение имён и типов моделей DeepSeek. */
public class ModelResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelResolver.class);

    private final Deepseek deepseek;

    public ModelResolver(Deepseek deepseek) {
        this.deepseek = deepseek;
    }

    /** Нормализация модели запроса: неизвестная → defaultModel (без thinking). */
    public String resolveModel(String model) {
        String requested = model == null ? "" : model.strip();
        if (requested.isEmpty()) {
            return deepseek.defaultModel();
        }
        String lowered = requested.toLowerCase(Locale.ROOT);
        if (lowered.equals(deepseek.defaultModel().toLowerCase(Locale.ROOT)) || lowered.contains("deepseek")) {
            return requested;
        }
        log.warn("Неизвестная модель '{}', заменяю на дефолтную '{}'", requested, deepseek.defaultModel());
        return deepseek.defaultModel();
    }

    public static String resolveModelType(String model) {
        String lowered = model.toLowerCase(Locale.ROOT);
        if (lowered.contains("reasoner") || lowered.contains("r1")) {
            return "deepseek-reasoner";
        }
        return null;
    }
}
