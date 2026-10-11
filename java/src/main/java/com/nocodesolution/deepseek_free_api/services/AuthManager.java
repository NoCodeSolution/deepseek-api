package com.nocodesolution.deepseek_free_api.services;

import com.nocodesolution.deepseek_free_api.browser.BrowserAuth;
import com.nocodesolution.deepseek_free_api.browser.BrowserAuthException;
import com.nocodesolution.deepseek_free_api.services.DeepSeekException.AuthRequiredException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AuthManager: состояние авторизации, тихий refresh, эскалация до интерактивного входа
 * (порт manager.py). Single-flight: ReentrantLock + generation-счётчик.
 */
public class AuthManager {

    private static final Logger log = LoggerFactory.getLogger(AuthManager.class);

    public enum AuthState {
        LOADING("loading"),
        READY("ready"),
        REFRESHING("refreshing"),
        EXPIRED("expired");

        private final String value;

        AuthState(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    private final AuthStorage authStorage;
    private final BrowserAuth browser;
    private final boolean interactive;
    private final Duration refreshInterval;
    private final String email;
    private final String password;

    private final ReentrantLock lock = new ReentrantLock();

    private volatile AuthState state = AuthState.LOADING;
    private volatile String cookieHeader = "";
    private volatile String token = "";
    private volatile Instant savedAt;
    private volatile String lastError = "";
    private volatile int generation = 0;

    private ScheduledExecutorService timer;

    public AuthManager(
            AuthStorage authStorage,
            BrowserAuth browser,
            boolean interactive,
            Duration refreshInterval,
            String email,
            String password) {
        this.authStorage = authStorage;
        this.browser = browser;
        this.interactive = interactive;
        this.refreshInterval = refreshInterval;
        this.email = email;
        this.password = password;
    }

    // ─── Состояние ────────────────────────────────────────────

    public AuthState state() {
        return state;
    }

    public Optional<Credentials> credentials() {
        String t = token;
        if (t.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Credentials(cookieHeader, t));
    }

    public Map<String, Object> statusMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("state", state.value());
        map.put("has_token", !token.isEmpty());
        map.put("token_saved_at", savedAt != null ? savedAt.toString() : null);
        map.put("interactive_available", interactive);
        map.put("last_error", lastError);
        return map;
    }

    private void apply(Credentials creds) {
        cookieHeader = creds.cookieHeader();
        token = creds.token();
        state = AuthState.READY;
        savedAt = Instant.now();
        lastError = "";
    }

    private void drop(String error) {
        token = "";
        cookieHeader = "";
        state = AuthState.EXPIRED;
        lastError = error;
    }

    private boolean hasCredentials() {
        return email != null && !email.isEmpty() && password != null && !password.isEmpty();
    }

    // ─── Жизненный цикл ───────────────────────────────────────

    public void startup() {
        Credentials saved = null;
        try {
            saved = authStorage.readSavedAuth();
        } catch (AuthStorageException | IOException exc) {
            log.warn("auth.json не прочитан: {}", exc.getMessage());
            drop(String.valueOf(exc.getMessage()));
        }
        if (saved != null) {
            apply(saved);
            log.info("Загружена сохранённая авторизация");
            if (silentRefresh()) {
                log.info("Токен обновлён из профиля при старте");
            }
        } else {
            // auth.json нет, но профиль браузера может быть жив — сначала silent
            if (silentRefresh()) {
                log.info("Авторизация восстановлена из профиля при старте");
            } else if (credentialsLogin()) {
                log.info("Авторизация получена headless-логином при старте");
            } else {
                String reason = "auth.json не найден";
                if (hasCredentials()) {
                    reason += "; headless-логин по кредам не удался (см. лог)";
                }
                drop(reason);
                log.warn("Нет сохранённой авторизации. Выполни: deepseek-free-api --login");
            }
        }
        long periodMs = refreshInterval.toMillis();
        if (periodMs > 0) {
            timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread t = new Thread(runnable, "auth-refresh");
                t.setDaemon(true);
                return t;
            });
            timer.scheduleWithFixedDelay(this::backgroundRefresh, periodMs, periodMs, TimeUnit.MILLISECONDS);
        }
    }

    public void shutdown() {
        ScheduledExecutorService t = timer;
        timer = null;
        if (t != null) {
            t.shutdownNow();
        }
    }

    private void backgroundRefresh() {
        try {
            if (silentRefresh()) {
                log.info("Фоновый refresh: токен обновлён");
            }
        } catch (Exception exc) {
            // таймер не должен умирать от исключений
            log.warn("Фоновый refresh не удался: {}", exc.getMessage());
        }
    }

    // ─── Refresh ──────────────────────────────────────────────

    private boolean silentRefresh() {
        lock.lock();
        try {
            Credentials creds = browser.refreshFromProfile();
            if (creds != null) {
                apply(creds);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    private boolean credentialsLogin() {
        if (!hasCredentials()) {
            return false;
        }
        Credentials creds;
        try {
            creds = browser.headlessCredentialsLogin();
        } catch (BrowserAuthException exc) {
            // Сообщения BrowserAuthException — свои, безопасны для лога
            log.warn("Headless-логин по кредам не удался: {}", exc.getMessage());
            return false;
        } catch (Exception exc) {
            // Сырые исключения Playwright могут содержать креды/DOM — логируем только тип
            log.warn("Headless-логин по кредам упал: {}", exc.getClass().getSimpleName());
            return false;
        }
        apply(creds);
        return true;
    }

    /**
     * Single-flight refresh: silent → headless по кредам → интерактивное окно.
     * Повторные вызовы подряд (пачка параллельных запросов с протухшим токеном)
     * не запускают браузер заново: увидев чужое успешное обновление, возвращаем
     * True сразу. Управляет поколением generation.
     */
    public boolean refresh() {
        return refresh(true);
    }

    public boolean refresh(boolean allowInteractive) {
        int seenGeneration = generation;
        lock.lock();
        try {
            if (!token.isEmpty() && generation != seenGeneration) {
                return true;
            }
            state = AuthState.REFRESHING;
            try {
                if (silentRefresh()) {
                    generation++;
                    log.info("Авторизация обновлена (silent refresh)");
                    return true;
                }
                log.warn("Silent refresh не помог, сессия профиля мертва");
                if (credentialsLogin()) {
                    generation++;
                    log.info("Авторизация восстановлена headless-логином по кредам");
                    return true;
                }
                if (allowInteractive && interactive) {
                    log.warn("Открываю окно логина...");
                    Credentials creds;
                    try {
                        creds = browser.loginAndSaveAuth();
                    } catch (BrowserAuthException exc) {
                        drop(exc.getMessage());
                        return false;
                    }
                    apply(creds);
                    generation++;
                    log.info("Авторизация восстановлена интерактивным входом");
                    return true;
                }
                drop("silent refresh не удался, интерактивный вход отключён");
                return false;
            } catch (Exception exc) {
                log.error("Refresh упал", exc);
                drop(exc.getMessage() != null ? exc.getMessage() : exc.toString());
                return false;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Принудительный интерактивный вход (эндпоинт /v1/auth/login). */
    public boolean loginNow() {
        lock.lock();
        try {
            state = AuthState.REFRESHING;
            Credentials creds;
            try {
                creds = browser.loginAndSaveAuth();
            } catch (BrowserAuthException exc) {
                drop(exc.getMessage());
                return false;
            }
            apply(creds);
            generation++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    // ─── Retry-обёртка ────────────────────────────────────────

    public <T> T callWithRefresh(Supplier<T> factory) {
        return callWithRefresh(factory, true);
    }

    /** Выполняет factory(); при AuthRequiredException — refresh и один повтор. */
    public <T> T callWithRefresh(Supplier<T> factory, boolean allowInteractive) {
        try {
            return factory.get();
        } catch (AuthRequiredException exc) {
            log.warn("Auth error от DeepSeek, пробуем refresh");
            if (refresh(allowInteractive)) {
                return factory.get();
            }
            throw exc;
        }
    }
}
