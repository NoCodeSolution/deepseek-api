package com.nocodesolution.deepseek_free_api.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocodesolution.deepseek_free_api.browser.BrowserAuth;
import com.nocodesolution.deepseek_free_api.browser.BrowserAuthException;
import com.nocodesolution.deepseek_free_api.enums.AuthState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 13 кейсов AuthManager. Браузер замокан — сети и браузера нет. */
@ExtendWith(MockitoExtension.class)
class AuthManagerTest {

    @TempDir
    Path tmpDir;

    @Mock
    BrowserAuth browser;

    Path authDir;
    AuthStorage storage;

    @BeforeEach
    void setUp() {
        authDir = tmpDir.resolve("auth");
        storage = new AuthStorage(authDir, "https://chat.deepseek.com");
    }

    private static Credentials creds(String token) {
        return new Credentials("ds_session_id=s", token);
    }

    private void writeAuth() throws IOException {
        Files.createDirectories(authDir);
        Files.writeString(
                authDir.resolve("auth.json"),
                "{\"version\": 1, \"userToken\": \"saved-tok\","
                        + " \"cookies\": [{\"name\": \"ds_session_id\", \"value\": \"s\"}]}",
                StandardCharsets.UTF_8);
    }

    private AuthManager manager(boolean interactive, String email, String password) {
        return new AuthManager(storage, browser, interactive, email, password);
    }

    @Test
    void refreshUsesHeadlessCreds() throws Exception {
        writeAuth();
        when(browser.refreshFromProfile()).thenReturn(null);
        when(browser.headlessCredentialsLogin()).thenReturn(creds("headless-tok"));
        AuthManager m = manager(true, "a@b.c", "pw");
        m.startup();
        assertTrue(m.refresh());
        assertEquals(AuthState.READY, m.state());
        assertEquals(Optional.of("headless-tok"), m.credentials().map(Credentials::token));
    }

    @Test
    void refreshHeadlessFailFallsBackToWindow() throws Exception {
        writeAuth();
        when(browser.refreshFromProfile()).thenReturn(null);
        when(browser.headlessCredentialsLogin()).thenThrow(new BrowserAuthException("капча"));
        when(browser.loginAndSaveAuth()).thenReturn(creds("login-tok"));
        AuthManager m = manager(true, "a@b.c", "pw");
        m.startup();
        assertTrue(m.refresh());
        assertEquals(Optional.of("login-tok"), m.credentials().map(Credentials::token));
    }

    @Test
    void refreshSkipsHeadlessWithoutCreds() throws Exception {
        writeAuth();
        when(browser.refreshFromProfile()).thenReturn(null);
        AuthManager m = manager(false, "", "");
        m.startup();
        assertFalse(m.refresh(false));
        verify(browser, never()).headlessCredentialsLogin();
    }

    @Test
    void startupHeadlessLoginWithoutAuthJson() throws Exception {
        when(browser.refreshFromProfile()).thenReturn(null);
        when(browser.headlessCredentialsLogin()).thenReturn(creds("headless-tok"));
        AuthManager m = manager(false, "a@b.c", "pw");
        m.startup();
        assertEquals(AuthState.READY, m.state());
        assertEquals(Optional.of("headless-tok"), m.credentials().map(Credentials::token));
    }

    @Test
    void startupNoAuthExpired() throws Exception {
        when(browser.refreshFromProfile()).thenReturn(null);
        AuthManager m = manager(false, "", "");
        m.startup();
        assertEquals(AuthState.EXPIRED, m.state());
        assertTrue(m.credentials().isEmpty());
    }

    @Test
    void startupSavedPlusSilentRefresh() throws Exception {
        writeAuth();
        when(browser.refreshFromProfile()).thenReturn(creds("fresh-tok"));
        AuthManager m = manager(false, "", "");
        m.startup();
        assertEquals(AuthState.READY, m.state());
        assertEquals(Optional.of("fresh-tok"), m.credentials().map(Credentials::token));
    }

    @Test
    void refreshSilentSuccess() throws Exception {
        writeAuth();
        AtomicInteger calls = new AtomicInteger();
        when(browser.refreshFromProfile()).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return creds("t");
        });
        AuthManager m = manager(false, "", "");
        m.startup();
        assertTrue(m.refresh(false));
        assertEquals(2, calls.get()); // startup + refresh
    }

    @Test
    void refreshEscalatesToInteractive() throws Exception {
        writeAuth();
        when(browser.refreshFromProfile()).thenReturn(null);
        when(browser.loginAndSaveAuth()).thenReturn(creds("login-tok"));
        AuthManager m = manager(true, "", "");
        m.startup();
        assertTrue(m.refresh());
        assertEquals(AuthState.READY, m.state());
        assertEquals(Optional.of("login-tok"), m.credentials().map(Credentials::token));
    }

    @Test
    void refreshNoInteractiveDrops() throws Exception {
        writeAuth();
        when(browser.refreshFromProfile()).thenReturn(null);
        AuthManager m = manager(false, "", "");
        m.startup();
        assertFalse(m.refresh(false));
        assertEquals(AuthState.EXPIRED, m.state());
        assertTrue(m.credentials().isEmpty());
    }

    @Test
    void loginNowFailureDrops() throws Exception {
        writeAuth();
        when(browser.refreshFromProfile()).thenReturn(null);
        when(browser.loginAndSaveAuth()).thenThrow(new BrowserAuthException("пользователь закрыл окно"));
        AuthManager m = manager(true, "", "");
        m.startup();
        assertFalse(m.loginNow());
        assertEquals(AuthState.EXPIRED, m.state());
    }

    @Test
    void singleflightDedup() throws Exception {
        writeAuth();
        AtomicInteger calls = new AtomicInteger();
        when(browser.refreshFromProfile()).thenAnswer(invocation -> {
            calls.incrementAndGet();
            Thread.sleep(50);
            return creds("t");
        });
        AuthManager m = manager(false, "", "");
        m.startup();

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Boolean> f1 = submitRefresh(pool, m, ready, go);
            Future<Boolean> f2 = submitRefresh(pool, m, ready, go);
            Future<Boolean> f3 = submitRefresh(pool, m, ready, go);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            assertTrue(f1.get(30, TimeUnit.SECONDS));
            assertTrue(f2.get(30, TimeUnit.SECONDS));
            assertTrue(f3.get(30, TimeUnit.SECONDS));
            assertEquals(2, calls.get()); // startup + ровно один refresh на троих
        } finally {
            pool.shutdownNow();
        }
    }

    private static Future<Boolean> submitRefresh(
            ExecutorService pool, AuthManager m, CountDownLatch ready, CountDownLatch go) {
        return pool.submit(() -> {
            ready.countDown();
            go.await();
            return m.refresh();
        });
    }

}
