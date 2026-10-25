package com.nocodesolution.deepseek_free_api.services.auth;

import com.nocodesolution.deepseek_free_api.exceptions.BrowserAuthException;
import com.nocodesolution.deepseek_free_api.enums.AuthState;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import com.nocodesolution.deepseek_free_api.exceptions.AuthStorageException;
import com.nocodesolution.deepseek_free_api.model.Credentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AuthManager управляет жизненным циклом авторизации DeepSeek.
 */
public class AuthManager {
    private static final Logger log = LoggerFactory.getLogger(AuthManager.class);

    private final AuthStorage authStorage;
    private final BrowserAuth browser;
    private final boolean interactive;
    private final String email;
    private final String password;

    private final AtomicReference<CompletableFuture<Boolean>> currentRefresh = new AtomicReference<>();
    private final ReentrantLock loginLock = new ReentrantLock();

    private volatile AuthState state = AuthState.LOADING;
    private volatile String cookieHeader = "";
    private volatile String token = "";
    private volatile Instant savedAt;
    private volatile String lastError = "";

    public AuthManager(
            AuthStorage authStorage,
            BrowserAuth browser,
            boolean interactive,
            String email,
            String password) {
        this.authStorage = authStorage;
        this.browser = browser;
        this.interactive = interactive;
        this.email = email;
        this.password = password;
    }

    // test-purposes
    protected AuthState state() {
        return state;
    }

    /** Текущие креды авторизации, если токен не пустой. */
    public Optional<Credentials> credentials() {
        String t = token;
        if (t.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Credentials(cookieHeader, t));
    }

    /** Статус авторизации для /health и /v1/auth/status. */
    public Map<String, Object> statusMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("state", state.value());
        map.put("has_token", !token.isEmpty());
        map.put("token_saved_at", savedAt != null ? savedAt.toString() : null);
        map.put("interactive_available", interactive);
        map.put("last_error", lastError);
        return map;
    }

    /** Устанавливает креды и переводит состояние в READY. */
    private void apply(Credentials creds) {
        cookieHeader = creds.cookieHeader();
        token = creds.token();
        state = AuthState.READY;
        savedAt = Instant.now();
        lastError = "";
    }

    /** Сбрасывает креды, переводит состояние в EXPIRED и сохраняет причину. */
    private void drop(String error) {
        token = "";
        cookieHeader = "";
        state = AuthState.EXPIRED;
        lastError = error;
    }

    private boolean hasCredentials() {
        return email != null && !email.isEmpty() && password != null && !password.isEmpty();
    }

    /**
     * Вызывается один раз при старте приложения.
     * Порядок: saved auth → silent refresh → headless login → EXPIRED.
     */
    public void startup() {
        Optional<Credentials> saved = loadSavedAuth();
        if (saved.isPresent()) {
            apply(saved.get());
            log.info("Загружена сохранённая авторизация");
            trySilentRefresh().ifPresentOrElse(
                    creds -> {
                        apply(creds);
                        log.info("Токен обновлён из профиля при старте");
                    },
                    () -> log.warn("Silent refresh при старте не удался"));
        } else {
            Optional<Credentials> creds = trySilentRefresh().or(this::tryHeadlessLogin);
            if (creds.isPresent()) {
                apply(creds.get());
                log.info("Авторизация восстановлена из профиля/headless при старте");
            } else {
                String reason = "auth.json не найден";
                if (hasCredentials()) {
                    reason += "; headless-логин по кредам не удался";
                }
                drop(reason);
                log.warn("Нет сохранённой авторизации. Выполните: deepseek-free-api --login");
            }
        }
    }

    public boolean refresh() {
        return refresh(true);
    }

    /**
     * Попытка обновить авторизацию.
     *
     * @param allowInteractive false — не открывать окно логина, даже если профиль мёртв
     */
    public boolean refresh(boolean allowInteractive) {
        while (true) {
            CompletableFuture<Boolean> existing = currentRefresh.get();
            if (existing != null && !existing.isDone()) {
                return await(existing);
            }
            CompletableFuture<Boolean> newFuture = new CompletableFuture<>();
            if (currentRefresh.compareAndSet(existing, newFuture)) {
                try {
                    boolean result = performRefresh(allowInteractive);
                    newFuture.complete(result);
                    return result;
                } catch (RuntimeException e) {
                    newFuture.completeExceptionally(e);
                    return false;
                } finally {
                    currentRefresh.compareAndSet(newFuture, null);
                }
            }
        }
    }

    /** Принудительный интерактивный вход */
    public boolean loginNow() {
        loginLock.lock();
        try {
            state = AuthState.REFRESHING;
            apply(tryInteractiveLogin());
            log.info("Авторизация восстановлена интерактивным входом");
            return true;
        } catch (BrowserAuthException e) {
            drop(e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("loginNow упал", e);
            drop(e.getMessage() != null ? e.getMessage() : e.toString());
            return false;
        } finally {
            loginLock.unlock();
        }
    }

    private static boolean await(CompletableFuture<Boolean> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }

    /** Читает сохранённую авторизацию из auth.json. */
    private Optional<Credentials> loadSavedAuth() {
        try {
            return Optional.ofNullable(authStorage.readSavedAuth());
        } catch (AuthStorageException | IOException e) {
            log.warn("auth.json не прочитан: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Тихое обновление из браузерного профиля; null/empty — профиль мёртв. */
    private Optional<Credentials> trySilentRefresh() {
        Credentials creds = browser.refreshFromProfile();
        return creds != null ? Optional.of(creds) : Optional.empty();
    }

    /** Headless-логин по кредам, если они заданы. */
    private Optional<Credentials> tryHeadlessLogin() {
        if (!hasCredentials()) {
            return Optional.empty();
        }
        try {
            return Optional.of(browser.headlessCredentialsLogin());
        } catch (BrowserAuthException e) {
            // Сообщения BrowserAuthException — свои, безопасны для лога
            log.warn("Headless-логин по кредам не удался: {}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            // Сырые исключения Playwright могут содержать креды/DOM — логируем только тип
            log.warn("Headless-логин по кредам упал: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Интерактивный вход через окно браузера. Бросает {@link BrowserAuthException} при неудаче. */
    private Credentials tryInteractiveLogin() throws BrowserAuthException {
        if (!interactive) {
            throw new BrowserAuthException("Интерактивный вход отключён");
        }
        log.warn("Открываю окно логина...");
        return browser.loginAndSaveAuth();
    }

    /**
     * Попытка обновления: silent → headless → interactive (optional).
     */
    private boolean performRefresh(boolean allowInteractive) {
        state = AuthState.REFRESHING;
        try {
            Optional<Credentials> creds = trySilentRefresh()
                    .or(this::tryHeadlessLogin);
            if (creds.isPresent()) {
                apply(creds.get());
                log.info("Авторизация обновлена (silent/headless)");
                return true;
            }

            if (allowInteractive) {
                try {
                    apply(tryInteractiveLogin());
                    log.info("Авторизация восстановлена интерактивным входом");
                    return true;
                } catch (BrowserAuthException e) {
                    drop(e.getMessage());
                    throw new RuntimeException("refresh failed", e);
                }
            }

            drop("silent refresh не удался, интерактивный вход отключён");
            throw new RuntimeException("refresh failed");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Refresh упал", e);
            drop(e.getMessage() != null ? e.getMessage() : e.toString());
            throw new RuntimeException("refresh failed", e);
        }
    }
}
