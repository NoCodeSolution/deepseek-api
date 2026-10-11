package com.nocodesolution.deepseek_free_api.services;

import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Детерминированное (regex, без ML) маскирование секретов в prompt до отправки в DeepSeek (план §7).
 * Конструктор без Spring; Spring-бин собирается в ServiceConfig.
 *
 * <p>Порядок детекторов — от специфичных к общим: PEM → пары key=value (съедают
 * «Authorization: Bearer …» целиком) → Bearer → JWT → sk-ключи → AWS → длинные токены →
 * IPv4 → IPv6 → email → URL → кастомные шаблоны.
 */
public final class PromptSanitizer {

    private static final Logger log = LoggerFactory.getLogger(PromptSanitizer.class);

    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
            Pattern.DOTALL);

    /** Ключ + разделитель сохраняются; значение (включая опциональный «Bearer …») заменяется на *** */
    private static final Pattern KV_PAIR = Pattern.compile(
            "\\b(password|passwd|pwd|secret|token|api_key|apikey|authorization|cookie)"
                    + "\"?(\\s*[=:]\\s*)\"?((?:Bearer\\s+)?[^\\s\"',;]+)\"?",
            Pattern.CASE_INSENSITIVE);

    /** Класс токена исключает '*', чтобы не срабатывать повторно на плейсхолдеры. */
    private static final Pattern BEARER = Pattern.compile(
            "\\b(Bearer)(\\s+)[A-Za-z0-9._~+/=-]+", Pattern.CASE_INSENSITIVE);

    private static final Pattern JWT = Pattern.compile(
            "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+(?![A-Za-z0-9_-])");

    private static final Pattern API_KEY = Pattern.compile(
            "(?<![A-Za-z0-9_-])sk-(?:proj-)?[A-Za-z0-9_-]{16,}(?![A-Za-z0-9_-])");

    private static final Pattern AWS_KEY = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");

    private static final Pattern LONG_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9+/=_-])[A-Za-z0-9+/=_-]{32,}(?![A-Za-z0-9+/=_-])");

    /** Октеты валидируются (<=255) в коде после матча. */
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");

    /** Полные и сжатые (::) формы; MAC (группы по 2 hex без '::') не матчится ни одной альтернативой. */
    private static final Pattern IPV6 = Pattern.compile(
            "(?<![0-9A-Fa-f:.])(?:"
                    + "(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}"
                    + "|(?:[0-9A-Fa-f]{1,4}:){1,7}:"
                    + "|(?:[0-9A-Fa-f]{1,4}:){1,6}:[0-9A-Fa-f]{1,4}"
                    + "|(?:[0-9A-Fa-f]{1,4}:){1,5}(?::[0-9A-Fa-f]{1,4}){1,2}"
                    + "|(?:[0-9A-Fa-f]{1,4}:){1,4}(?::[0-9A-Fa-f]{1,4}){1,3}"
                    + "|(?:[0-9A-Fa-f]{1,4}:){1,3}(?::[0-9A-Fa-f]{1,4}){1,4}"
                    + "|(?:[0-9A-Fa-f]{1,4}:){1,2}(?::[0-9A-Fa-f]{1,4}){1,5}"
                    + "|[0-9A-Fa-f]{1,4}:(?:(?::[0-9A-Fa-f]{1,4}){1,6})"
                    + "|:(?:(?::[0-9A-Fa-f]{1,4}){1,7}|:)"
                    + ")(?![0-9A-Fa-f:])");

    private static final Pattern EMAIL = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    private static final Pattern URL = Pattern.compile(
            "\\b(?:https?://|www\\.)[^\\s\"'<>()\\[\\]]+");

    private final boolean maskSecrets;
    private final List<Pattern> customPatterns;

    public PromptSanitizer(boolean maskSecrets, List<String> customPatterns) {
        this.maskSecrets = maskSecrets;
        this.customPatterns = (customPatterns == null ? List.<String>of() : customPatterns).stream()
                .filter(p -> p != null && !p.isBlank())
                .map(p -> {
                    try {
                        return Pattern.compile(p);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Невалидный privacy.mask-patterns regex: " + p, e);
                    }
                })
                .toList();
    }

    /** Маскирует секреты в prompt. При maskSecrets=false возвращает вход без изменений. */
    public String sanitize(String prompt) {
        if (!maskSecrets || prompt == null || prompt.isEmpty()) {
            return prompt;
        }
        String out = prompt;
        out = mask(out, "pem", PEM, "***private-key***");
        out = maskKeyValuePairs(out);
        out = maskBearer(out);
        out = mask(out, "jwt", JWT, "***jwt***");
        out = mask(out, "api-key", API_KEY, "***api-key***");
        out = mask(out, "aws-key", AWS_KEY, "***aws-key***");
        out = mask(out, "long-token", LONG_TOKEN, "***token***");
        out = maskIpv4(out);
        out = mask(out, "ipv6", IPV6, "***ip***");
        out = mask(out, "email", EMAIL, "***email***");
        out = mask(out, "url", URL, "***url***");
        for (Pattern custom : customPatterns) {
            out = mask(out, "custom(" + custom.pattern() + ")", custom, "***custom***");
        }
        return out;
    }

    private static String mask(String text, String detector, Pattern pattern, String replacement) {
        return pattern.matcher(text).replaceAll(mr -> {
            logMask(detector, mr);
            return Matcher.quoteReplacement(replacement);
        });
    }

    private static String maskKeyValuePairs(String text) {
        return KV_PAIR.matcher(text).replaceAll(mr -> {
            logMask("kv-pair", mr);
            return Matcher.quoteReplacement(mr.group(1) + mr.group(2) + "***");
        });
    }

    private static String maskBearer(String text) {
        return BEARER.matcher(text).replaceAll(mr -> {
            logMask("bearer", mr);
            return Matcher.quoteReplacement(mr.group(1) + mr.group(2) + "***");
        });
    }

    private static String maskIpv4(String text) {
        return IPV4.matcher(text).replaceAll(mr -> {
            for (String octet : mr.group().split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    return Matcher.quoteReplacement(mr.group());
                }
            }
            logMask("ipv4", mr);
            return "***ip***";
        });
    }

    /** Логируются только метаданные замены — само значение секрета в лог не попадает. */
    private static void logMask(String detector, MatchResult mr) {
        log.info("prompt mask: detector={} pos={} len={}", detector, mr.start(), mr.group().length());
    }
}
