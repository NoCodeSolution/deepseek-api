package com.nocodesolution.deepseek_free_api.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Тесты PromptSanitizer (план §7): детерминированное маскирование секретов перед отправкой в DeepSeek. */
class PromptSanitizerTest {

    private static final String JWT =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MiJ9.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJVadQssw5c";

    private static PromptSanitizer sanitizer() {
        return new PromptSanitizer(true, List.of());
    }

    @Test
    void disabledReturnsInputByteForByte() {
        String prompt = "password=hunter2\nJWT: " + JWT + "\nurl https://example.com/x?token=abc\n"
                + "ip 10.1.2.3 email a@b.co Bearer abcdef1234567890";
        PromptSanitizer off = new PromptSanitizer(false, List.of("DS_SECRET_[A-Z0-9]+"));
        assertEquals(prompt, off.sanitize(prompt));
    }

    @Test
    void masksPemBlock() {
        String prompt = "Вот мой ключ:\n"
                + "-----BEGIN RSA PRIVATE KEY-----\n"
                + "MIIEpAIBAAKCAQEA7\n"
                + "VGk6KQIDAQABAoIBAAD\n"
                + "-----END RSA PRIVATE KEY-----\n"
                + "И вот мой вопрос.";
        String out = sanitizer().sanitize(prompt);
        assertEquals("Вот мой ключ:\n***private-key***\nИ вот мой вопрос.", out);
    }

    @Test
    void masksJwt() {
        String prompt = "Авторизуйся токеном " + JWT + " и продолжи";
        assertEquals("Авторизуйся токеном ***jwt*** и продолжи", sanitizer().sanitize(prompt));
    }

    @Test
    void masksOpenAiStyleKeys() {
        assertEquals(
                "ключ ***api-key*** тут",
                sanitizer().sanitize("ключ sk-Ab3dEf5Gh7jK9mN1pQ3rS тут"));
        assertEquals(
                "ключ ***api-key*** тут",
                sanitizer().sanitize("ключ sk-proj-Ab3dEf5Gh7jK9mN1pQ3rS тут"));
    }

    @Test
    void masksAwsAccessKey() {
        assertEquals(
                "aws: ***aws-key*** end",
                sanitizer().sanitize("aws: AKIAIOSFODNN7EXAMPLE end"));
    }

    @Test
    void masksSecretKeyValuePairs() {
        PromptSanitizer s = sanitizer();
        assertEquals("password=***", s.sanitize("password=hunter2"));
        assertEquals("passwd: ***", s.sanitize("passwd: hunter2"));
        assertEquals("pwd = ***", s.sanitize("pwd = \"hunter2\""));
        assertEquals("secret=***", s.sanitize("secret=abc"));
        assertEquals("token: ***", s.sanitize("token: t0k3n-value"));
        assertEquals("api_key=***", s.sanitize("api_key=zzz"));
        assertEquals("apikey: ***", s.sanitize("apikey: qq"));
        assertEquals("cookie: ***", s.sanitize("cookie: session=abc"));
        assertEquals("AUTHORIZATION: ***", s.sanitize("AUTHORIZATION: Bearer xyz"));
        // ключ и разделитель сохраняются, окружающий текст не тронут
        assertEquals("строка password=*** конец", s.sanitize("строка password=hunter2 конец"));
    }

    @Test
    void masksStandaloneBearerToken() {
        assertEquals(
                "Заголовок: Bearer *** дальше",
                sanitizer().sanitize("Заголовок: Bearer abcdef123456._-+xyz= дальше"));
    }

    @Test
    void authorizationBearerPairMasksWholeValue() {
        // коллизия детекторов: Bearer внутри Authorization-пары не должен оставлять хвостов
        String in = "Authorization: Bearer " + JWT;
        assertEquals("Authorization: ***", sanitizer().sanitize(in));
    }

    @Test
    void masksLongTokenButNotShort() {
        assertEquals(
                "отпечаток ***token*** ok",
                sanitizer().sanitize("отпечаток 5f4dcc3b5aa765d61d8327deb882cf99 ok"));
        String short31 = "a".repeat(31);
        assertEquals("x " + short31 + " y", sanitizer().sanitize("x " + short31 + " y"));
    }

    @Test
    void masksIpv4IncludingVersionLikeNumbers() {
        assertEquals(
                "сервер ***ip*** упал",
                sanitizer().sanitize("сервер 192.168.100.200 упал"));
        // зафиксированное ожидание: «версия 1.2.3.4» маскируется как IP
        assertEquals(
                "версия ***ip*** пакета",
                sanitizer().sanitize("версия 1.2.3.4 пакета"));
    }

    @Test
    void doesNotMaskInvalidIpv4() {
        assertEquals(
                "адрес 999.999.1.1 не трогаем",
                sanitizer().sanitize("адрес 999.999.1.1 не трогаем"));
        assertEquals(
                "адрес 256.10.0.1 не трогаем",
                sanitizer().sanitize("адрес 256.10.0.1 не трогаем"));
    }

    @Test
    void masksIpv6FullAndCompressed() {
        PromptSanitizer s = sanitizer();
        assertEquals(
                "addr ***ip*** end",
                s.sanitize("addr 2001:0db8:85a3:0000:0000:8a2e:0370:7334 end"));
        assertEquals("addr ***ip*** end", s.sanitize("addr 2001:db8::1 end"));
        assertEquals("addr ***ip*** end", s.sanitize("addr ::1 end"));
    }

    @Test
    void doesNotMaskMacAddress() {
        String prompt = "mac aa:bb:cc:dd:ee:ff сервера, время 12:34:56";
        assertEquals(prompt, sanitizer().sanitize(prompt));
    }

    @Test
    void masksEmail() {
        assertEquals(
                "пиши на ***email*** срочно",
                sanitizer().sanitize("пиши на user.name+tag@sub.example.co.uk срочно"));
    }

    @Test
    void masksUrls() {
        PromptSanitizer s = sanitizer();
        assertEquals(
                "see ***url*** end",
                s.sanitize("see https://user:pass@host.example.com:8080/p?q=1&r=2#frag end"));
        assertEquals("see ***url*** end", s.sanitize("see http://example.com/a end"));
        assertEquals("see ***url*** end", s.sanitize("see www.example.com/page.html end"));
    }

    @Test
    void masksUuidLikeHexAsToken() {
        // зафиксированное ожидание: 32 hex подряд маскируются как token
        assertEquals(
                "uuid ***token*** here",
                sanitizer().sanitize("uuid 550e8400e29b41d4a716446655440000 here"));
        // UUID с дефисами — 36 непрерывных символов из token-класса (дефис входит в класс),
        // тоже маскируется: зафиксированное ожидание
        assertEquals(
                "uuid ***token*** here",
                sanitizer().sanitize("uuid 550e8400-e29b-41d4-a716-446655440000 here"));
    }

    @Test
    void masksCombinedFixtureAndKeepsPlainText() {
        String prompt = "Deploy config:\n"
                + "endpoint https://api.example.com:8443/v1/chat?token=abc#top\n"
                + "contact admin@example.com\n"
                + "password=SuperSecret123\n"
                + "aws AKIAIOSFODNN7EXAMPLE\n"
                + "jwt " + JWT + "\n"
                + "host 10.0.0.1 / 2001:db8::1\n"
                + "fingerprint 5f4dcc3b5aa765d61d8327deb882cf99\n"
                + "Plain sentence stays.";
        String expected = "Deploy config:\n"
                + "endpoint ***url***\n"
                + "contact ***email***\n"
                + "password=***\n"
                + "aws ***aws-key***\n"
                + "jwt ***jwt***\n"
                + "host ***ip*** / ***ip***\n"
                + "fingerprint ***token***\n"
                + "Plain sentence stays.";
        assertEquals(expected, sanitizer().sanitize(prompt));
    }

    @Test
    void leavesPlainTextUntouched() {
        String prompt = "Привет! Расскажи про кота. Время 12:34:56, дата 2024-01-01, версия 0.9, сумма 1 000 000.";
        assertEquals(prompt, sanitizer().sanitize(prompt));
    }

    @Test
    void appliesCustomPatternsOnTopOfBuiltins() {
        PromptSanitizer s = new PromptSanitizer(true, List.of("DS_SECRET_[A-Z0-9]+"));
        assertEquals(
                "значение ***custom*** конец",
                s.sanitize("значение DS_SECRET_ABC123 конец"));
        // встроенные детекторы продолжают работать
        assertEquals(
                "***custom*** и ***email***",
                s.sanitize("DS_SECRET_Z9 и a@b.co"));
    }

    @Test
    void isIdempotent() {
        String prompt = "password=hunter2 ip 10.0.0.1 url https://x.io/a?b=1 email a@b.co\n"
                + "jwt " + JWT + " bearer Bearer tok123 fingerprint 5f4dcc3b5aa765d61d8327deb882cf99";
        PromptSanitizer s = sanitizer();
        String once = s.sanitize(prompt);
        assertEquals(once, s.sanitize(once));
    }

    @Test
    void logsDetectorMetadataButNotSecretValue() {
        Logger logger = (Logger) LoggerFactory.getLogger(PromptSanitizer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            sanitizer().sanitize("creds password=TopSecretValue9 end");
        } finally {
            logger.detachAppender(appender);
        }
        assertFalse(appender.list.isEmpty());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.INFO, event.getLevel());
        String msg = event.getFormattedMessage();
        assertTrue(msg.contains("kv-pair"), msg);
        assertFalse(msg.contains("TopSecretValue9"), msg);
    }
}
