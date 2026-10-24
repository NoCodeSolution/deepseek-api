package com.nocodesolution.deepseek_free_api.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import com.nocodesolution.deepseek_free_api.auth.CookieHeaderBuilder;
import com.nocodesolution.deepseek_free_api.auth.TokenNormalizer;
import com.nocodesolution.deepseek_free_api.services.AuthStorage;
import com.nocodesolution.deepseek_free_api.services.Credentials;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Браузерная авторизация через Playwright (синхронный API). */
public class BrowserAuthService implements BrowserAuth {

    public static final String DEFAULT_BASE_URL = "https://chat.deepseek.com";

    private static final Logger log = LoggerFactory.getLogger(BrowserAuthService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> LAUNCH_ARGS = List.of("--disable-blink-features=AutomationControlled");
    private static final int LOGIN_TIMEOUT_S = 300;
    private static final String API_PATH_MARKER = "/api/v0/";
    private static final String SESSION_COOKIE = CookieHeaderBuilder.SESSION_COOKIE;
    private static final String LOCALSTORAGE_TOKEN_JS =
            "() => { try { return localStorage.getItem('userToken'); } catch { return null; } }";

    private final String baseUrl;
    private final Path browserProfile;
    private final String email;
    private final String password;
    private final double headlessLoginTimeoutS;
    private final AuthStorage authStorage;

    public BrowserAuthService(
            String baseUrl,
            Path browserProfile,
            String email,
            String password,
            double headlessLoginTimeoutS,
            AuthStorage authStorage) {
        this.baseUrl = baseUrl;
        this.browserProfile = browserProfile;
        this.email = email;
        this.password = password;
        this.headlessLoginTimeoutS = headlessLoginTimeoutS;
        this.authStorage = authStorage;
    }

    // ─── Режимы ────────────────────────────────────────────────────────────

    @Override
    public Credentials refreshFromProfile() throws BrowserAuthException {
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context;
            try {
                context = launchPersistent(playwright, true, null);
            } catch (BrowserAuthException exc) {
                log.debug("Silent refresh: браузер недоступен: {}", exc.getMessage());
                return null;
            }
            try {
                Page page = firstPage(context);
                try {
                    page.navigate(baseUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                } catch (Exception ignored) {
                    // страница может не догрузиться — куки и localStorage всё равно читаем
                }
                List<Cookie> cookies = context.cookies(List.of(baseUrl));
                String token = readToken(page);
                if (token.isEmpty() || !hasSessionCookie(cookies)) {
                    return null;
                }
                return saveSession(cookies, token, false);
            } finally {
                context.close();
            }
        }
    }

    @Override
    public Credentials headlessCredentialsLogin() throws BrowserAuthException {
        if (email == null || email.isEmpty() || password == null || password.isEmpty()) {
            throw new BrowserAuthException("DS_EMAIL/DS_PASSWORD не заданы");
        }
        log.info("Headless-логин по кредам для {}", maskedEmail(email));
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = launchPersistent(playwright, true, scrubbedEnv());
            try {
                Page page = firstPage(context);
                page.navigate(baseUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                AuthApiWait wait = new AuthApiWait(Duration.ofMillis((long) (headlessLoginTimeoutS * 1000)));
                context.onResponse(wait::onResponse);
                try {
                    fillLoginForm(page);
                    wait.await();
                } catch (RuntimeException exc) {
                    wait.cancel();
                    throw exc;
                }
                return saveSession(context.cookies(List.of(baseUrl)), readToken(page), true);
            } finally {
                context.close();
            }
        }
    }

    @Override
    public Credentials loginAndSaveAuth() throws BrowserAuthException {
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = launchPersistent(playwright, false, null);
            try {
                Page page = firstPage(context);
                page.navigate(baseUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                log.info("Откроется окно DeepSeek. Выполните вход любым способом.\n"
                        + "   Окно закроется после успешного входа.");
                AuthApiWait wait = new AuthApiWait(Duration.ofSeconds(LOGIN_TIMEOUT_S));
                context.onResponse(wait::onResponse);
                wait.await();
                return saveSession(context.cookies(List.of(baseUrl)), readToken(page), true);
            } finally {
                context.close();
            }
        }
    }

    @Override
    public void clearProfileSession() throws BrowserAuthException {
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = launchPersistent(playwright, true, null);
            try {
                context.clearCookies();
            } finally {
                context.close();
            }
        }
    }

    @Override
    public Credentials connectToRunningChrome(int cdpPort) throws BrowserAuthException {
        String cdpUrl = "http://127.0.0.1:" + cdpPort;
        log.info("Подключение к Chrome на {}...", cdpUrl);
        try (Playwright playwright = Playwright.create()) {
            Browser browser;
            try {
                browser = playwright.chromium().connectOverCDP(cdpUrl);
            } catch (Exception exc) {
                throw new BrowserAuthException(
                        "Не удалось подключиться к Chrome.\n"
                                + "1. Закройте весь Chrome\n"
                                + "2. Запустите его заново этой командой в терминале:\n"
                                + "   chrome --remote-debugging-port=" + cdpPort + "\n"
                                + "   (Windows: \"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\" "
                                + "--remote-debugging-port=" + cdpPort + ")\n",
                        exc);
            }
            try {
                BrowserContext context =
                        !browser.contexts().isEmpty() ? browser.contexts().get(0) : browser.newContext();
                Page page = null;
                for (BrowserContext ctx : browser.contexts()) {
                    for (Page candidate : ctx.pages()) {
                        if (candidate.url().startsWith(baseUrl)) {
                            page = candidate;
                            break;
                        }
                    }
                    if (page != null) {
                        break;
                    }
                }
                if (page == null) {
                    page = context.newPage();
                }
                page.navigate(baseUrl, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(15000));
                String token = readToken(page);
                List<Cookie> cookies = context.cookies(List.of(baseUrl));
                if (token.isEmpty()) {
                    log.info("Вы не выполнили вход в DeepSeek. Открывается страница для входа.\n"
                            + "   После входа нажмите Enter в этом терминале.");
                    page.navigate(baseUrl + "/");
                    try {
                        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                    } catch (IOException exc) {
                        throw new BrowserAuthException(
                                "Не удалось дождаться подтверждения из терминала: " + exc.getMessage(), exc);
                    }
                    token = readToken(page);
                    cookies = context.cookies(List.of(baseUrl));
                    if (token.isEmpty() || !hasSessionCookie(cookies)) {
                        throw new BrowserAuthException("Логин не подтверждён. Нет userToken или ds_session_id.");
                    }
                    return saveSession(cookies, token, true);
                }
                return saveSession(cookies, token, true);
            } finally {
                browser.close();
            }
        }
    }

    // ─── Запуск браузера ──────────────────────────────────────

    private BrowserContext launchPersistent(Playwright playwright, boolean headless, Map<String, String> env)
            throws BrowserAuthException {
        try {
            Files.createDirectories(browserProfile);
        } catch (IOException exc) {
            throw new BrowserAuthException("Не удалось создать каталог профиля: " + exc.getMessage(), exc);
        }
        try {
            Files.setPosixFilePermissions(browserProfile, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // best-effort, права 0o700
        }
        try {
            return tryLaunch(playwright, headless, env);
        } catch (BrowserAuthException error) {
            if (!error.getMessage().contains("SingletonLock")) {
                throw new BrowserAuthException(
                        "Не удалось открыть браузер. Установите Google Chrome или выполните "
                                + "\"playwright install chromium\". " + error.getMessage(),
                        error);
            }
            for (String name : List.of("SingletonLock", "SingletonCookie", "SingletonSocket")) {
                try {
                    Files.deleteIfExists(browserProfile.resolve(name));
                } catch (IOException ignored) {
                    // best-effort
                }
            }
            try {
                return tryLaunch(playwright, headless, env);
            } catch (BrowserAuthException retryError) {
                throw new BrowserAuthException("Не удалось открыть браузер: " + retryError.getMessage(), retryError);
            }
        }
    }

    private BrowserContext tryLaunch(Playwright playwright, boolean headless, Map<String, String> env)
            throws BrowserAuthException {
        try {
            return playwright.chromium().launchPersistentContext(
                    browserProfile,
                    new BrowserType.LaunchPersistentContextOptions()
                            .setHeadless(headless)
                            .setViewportSize(null)
                            .setArgs(LAUNCH_ARGS)
                            .setChannel("chrome")
                            .setEnv(env));
        } catch (Exception chromeExc) {
            try {
                return playwright.chromium().launchPersistentContext(
                        browserProfile,
                        new BrowserType.LaunchPersistentContextOptions()
                                .setHeadless(headless)
                                .setViewportSize(null)
                                .setArgs(LAUNCH_ARGS)
                                .setEnv(env));
            } catch (Exception chromiumExc) {
                throw new BrowserAuthException("Chrome: " + chromeExc + ". Chromium: " + chromiumExc);
            }
        }
    }

    // ─── Сессия ─────────────────────────────────────────────────

    private Page firstPage(BrowserContext context) {
        return !context.pages().isEmpty() ? context.pages().get(0) : context.newPage();
    }

    private String readToken(Page page) {
        Object raw = page.evaluate(LOCALSTORAGE_TOKEN_JS);
        return TokenNormalizer.normalizeToken(raw == null ? "" : String.valueOf(raw));
    }

    private static boolean hasSessionCookie(List<Cookie> cookies) {
        for (Cookie c : cookies) {
            if (SESSION_COOKIE.equals(c.name)) {
                return true;
            }
        }
        return false;
    }

    private Credentials saveSession(List<Cookie> cookies, String token, boolean validate) throws BrowserAuthException {
        List<JsonNode> cookieJson = new ArrayList<>();
        for (Cookie c : cookies) {
            cookieJson.add(cookieToJson(c));
        }
        if (validate) {
            validateSession(cookieJson, token);
        }
        try {
            authStorage.writeSavedAuth(cookieJson, token);
        } catch (IOException exc) {
            throw new BrowserAuthException("Не удалось сохранить auth.json: " + exc.getMessage(), exc);
        }
        return new Credentials(CookieHeaderBuilder.cookieHeaderFromArray(AuthStorage.toArray(cookieJson)), token);
    }

    private static void validateSession(List<JsonNode> cookies, String token) throws BrowserAuthException {
        if (token == null || token.isEmpty()) {
            throw new BrowserAuthException("В localStorage нет userToken. Логин не завершён.");
        }
        if (!cookies.stream().anyMatch(c -> SESSION_COOKIE.equals(c.path("name").asText()))) {
            throw new BrowserAuthException("В куках нет ds_session_id. Логин не завершён.");
        }
    }

    private static JsonNode cookieToJson(Cookie c) {
        var node = MAPPER.createObjectNode();
        node.put("name", c.name);
        node.put("value", c.value);
        if (c.domain != null) {
            node.put("domain", c.domain);
        }
        if (c.path != null) {
            node.put("path", c.path);
        }
        if (c.expires != null) {
            node.put("expires", c.expires);
        }
        if (c.httpOnly != null) {
            node.put("httpOnly", c.httpOnly);
        }
        if (c.secure != null) {
            node.put("secure", c.secure);
        }
        if (c.sameSite != null) {
            node.put("sameSite", c.sameSite.name().charAt(0)
                    + c.sameSite.name().substring(1).toLowerCase(Locale.ROOT));
        }
        return node;
    }

    // ─── Ожидание авторизованного вызова API ──

    private static final class AuthApiWait {
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final Duration timeout;
        private final long startedNanos = System.nanoTime();

        AuthApiWait(Duration timeout) {
            this.timeout = timeout;
        }

        void onResponse(Response response) {
            if (done.isDone()) {
                return;
            }
            try {
                if (!response.url().contains(API_PATH_MARKER) || response.status() != 200) {
                    return;
                }
                String auth = authorizationHeader(response.request());
                if (!auth.toLowerCase(Locale.ROOT).startsWith("bearer ") || auth.length() < 17) {
                    return;
                }
                Thread.ofVirtual().name("auth-api-settle").start(() -> settleAndComplete(response));
            } catch (Exception ignored) {
                // слушатель не должен падать
            }
        }

        /** Чтение тела — на отдельном потоке, чтобы не блокировать поток событий Playwright. */
        private void settleAndComplete(Response response) {
            try {
                JsonNode body = MAPPER.readTree(response.body());
                if (body != null && body.isObject() && body.path("code").asLong(0) != 0) {
                    return;
                }
                Thread.sleep(800); // даём ответу «осесть», как в Node-версии
                done.complete(null);
            } catch (Exception ignored) {
                // тело не прочиталось — ждём следующий подходящий ответ
            }
        }

        void await() throws BrowserAuthException {
            long timeoutMs = timeout.toMillis();
            long remainingMs = timeoutMs - (System.nanoTime() - startedNanos) / 1_000_000;
            try {
                done.get(Math.max(1, remainingMs), TimeUnit.MILLISECONDS);
            } catch (TimeoutException exc) {
                throw new BrowserAuthException(
                        "Таймаут входа " + timeout.toSeconds() + " с. Выполните вход в окне DeepSeek.");
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
                throw new BrowserAuthException("Ожидание входа прервано");
            } catch (ExecutionException exc) {
                throw new BrowserAuthException("Ожидание входа завершилось ошибкой: " + exc.getMessage());
            }
        }

        void cancel() {
            done.cancel(true);
        }
    }

    private static String authorizationHeader(Request request) {
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            if (header.getKey().equalsIgnoreCase("authorization")) {
                return header.getValue();
            }
        }
        return "";
    }

    // ─── Форма логина ─────────────────────────────────────────

    private void fillLoginForm(Page page) throws BrowserAuthException {
        Locator emailInput = page.locator(
                "input[type=\"email\"], input[name=\"email\"], input[placeholder*=\"mail\" i]").first();
        try {
            emailInput.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(15000));
        } catch (Exception exc) {
            throw new BrowserAuthException(
                    "Не нашёл форму логина (поле email) — возможно капча или Cloudflare", exc);
        }
        emailInput.fill(email);

        Locator passwordInput = page.locator("input[type=\"password\"]").first();
        try {
            passwordInput.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(5000));
        } catch (Exception exc) {
            throw new BrowserAuthException("Не нашёл поле пароля в форме логина", exc);
        }
        passwordInput.fill(password);

        Locator checkbox = page.locator("input[type=\"checkbox\"]").first();
        try {
            if (checkbox.isVisible(new Locator.IsVisibleOptions().setTimeout(1000)) && !checkbox.isChecked()) {
                checkbox.check();
            }
        } catch (Exception ignored) {
            // чекбокс необязателен
        }

        Locator submit = page.locator(
                "button[type=\"submit\"], button:has-text(\"Log in\"), button:has-text(\"登录\")").first();
        try {
            submit.waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(5000));
        } catch (Exception exc) {
            throw new BrowserAuthException("Не нашёл кнопку входа в форме логина", exc);
        }
        submit.click();
    }

    // ─── Утилиты ────────────────────────────────────────────────

    private static String maskedEmail(String email) {
        int at = email.indexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return "***";
        }
        return email.substring(0, 1) + "***@" + email.substring(at + 1);
    }

    /** Окружение для Chromium без DS_* кредов (браузер не должен их видеть). */
    private static Map<String, String> scrubbedEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        System.getenv().forEach((key, value) -> {
            if (!key.startsWith("DS_")) {
                env.put(key, value);
            }
        });
        return env;
    }
}
