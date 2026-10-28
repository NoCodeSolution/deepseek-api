package com.nocodesolution.deepseek_free_api.services.auth;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.Cookie;
import com.nocodesolution.deepseek_free_api.exceptions.BrowserAuthException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class BrowserAuthServiceTest {

    private static final String BASE_URL = "https://chat.deepseek.com";

    @TempDir
    Path tempDir;

    private AuthStorage authStorage;
    private BrowserAuthService service;

    @BeforeEach
    void setUp() {
        authStorage = mock(AuthStorage.class);
        service = new BrowserAuthService(
                BASE_URL, tempDir.resolve("browser-profile"), "qa@example.invalid", "password", 5, authStorage);
    }

    @Test
    void currentLoginFormUsesLocaleIndependentLocators() throws Exception {
        Page page = mock(Page.class);
        Locator email = mock(Locator.class);
        Locator password = mock(Locator.class);
        Locator submit = mock(Locator.class);
        stubLoginForm(page, email, password, submit);

        invokeFillLoginForm(page);

        verify(email).fill("qa@example.invalid");
        verify(password).fill("password");
        verify(submit).click();
    }

    @Test
    void chromeAndChromiumReceiveCustomUserAgent() {
        Playwright playwright = mock(Playwright.class);
        BrowserType browserType = mock(BrowserType.class);
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        when(playwright.chromium()).thenReturn(browserType);
        when(browserType.launchPersistentContext(any(), any()))
                .thenThrow(new RuntimeException("Chrome unavailable"))
                .thenReturn(context);
        when(context.pages()).thenReturn(List.of(page));

        try (MockedStatic<Playwright> playwrightStatic = mockStatic(Playwright.class)) {
            playwrightStatic.when(Playwright::create).thenReturn(playwright);
            assertNull(service.refreshFromProfile());
        }

        ArgumentCaptor<BrowserType.LaunchPersistentContextOptions> options =
                ArgumentCaptor.forClass(BrowserType.LaunchPersistentContextOptions.class);
        verify(browserType, times(2)).launchPersistentContext(any(), options.capture());
        for (BrowserType.LaunchPersistentContextOptions launch : options.getAllValues()) {
            assertTrue(launch.args.stream().anyMatch(arg ->
                    arg.startsWith("--user-agent=Mozilla/5.0") && !arg.contains("HeadlessChrome")));
            assertTrue(launch.args.contains("--disable-blink-features=AutomationControlled"));
            assertTrue(launch.args.contains("--no-first-run"));
            assertTrue(launch.args.contains("--no-default-browser-check"));
        }
    }

    @Test
    void silentRefreshRejectsPlaceholderSessionOnLoginPage() throws Exception {
        Playwright playwright = mock(Playwright.class);
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        stubLaunch(playwright, context, page);
        stubSession(context, page);
        when(page.url()).thenReturn(BASE_URL + "/sign_in");

        try (MockedStatic<Playwright> playwrightStatic = mockStatic(Playwright.class)) {
            playwrightStatic.when(Playwright::create).thenReturn(playwright);
            assertNull(service.refreshFromProfile());
        }

        verify(authStorage, times(0)).writeSavedAuth(any(), any());
        verify(context).close();
        verify(playwright).close();
    }

    @Test
    void headlessLoginClosesBrowserAfterSuccess() throws Exception {
        Playwright playwright = mock(Playwright.class);
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        stubLaunch(playwright, context, page);
        stubLoginForm(page, mock(Locator.class), mock(Locator.class), mock(Locator.class));
        stubNoVerification(page);
        stubSuccessfulAuth(context, page);

        try (MockedStatic<Playwright> playwrightStatic = mockStatic(Playwright.class)) {
            playwrightStatic.when(Playwright::create).thenReturn(playwright);
            service.headlessCredentialsLogin();
        }

        verify(context).close();
        verify(playwright).close();
    }

    @Test
    void headlessLoginAcceptsVerificationCodeFromConsole() throws Exception {
        Playwright playwright = mock(Playwright.class);
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        Locator codeInput = mock(Locator.class);
        Locator sendCode = mock(Locator.class);
        Locator verifyButton = mock(Locator.class);
        stubLaunch(playwright, context, page);
        stubLoginForm(page, mock(Locator.class), mock(Locator.class), mock(Locator.class));
        stubVerification(page, codeInput, sendCode, verifyButton);
        stubSession(context, page);

        InputStream originalInput = System.in;
        System.setIn(new ByteArrayInputStream("123456\n".getBytes(StandardCharsets.UTF_8)));
        try (MockedStatic<Playwright> playwrightStatic = mockStatic(Playwright.class)) {
            playwrightStatic.when(Playwright::create).thenReturn(playwright);
            service.headlessCredentialsLogin();
        } finally {
            System.setIn(originalInput);
        }

        verify(sendCode, times(0)).click();
        verify(codeInput).fill("123456");
        verify(verifyButton).click();
        verify(context).close();
        verify(playwright).close();
    }

    @Test
    void headlessLoginRejectsInvalidVerificationCodeAndClosesBrowser() {
        Playwright playwright = mock(Playwright.class);
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        Locator codeInput = mock(Locator.class);
        Locator sendCode = mock(Locator.class);
        Locator verifyButton = mock(Locator.class);
        stubLaunch(playwright, context, page);
        stubLoginForm(page, mock(Locator.class), mock(Locator.class), mock(Locator.class));
        stubVerification(page, codeInput, sendCode, verifyButton);
        stubSuccessfulAuth(context, page);

        InputStream originalInput = System.in;
        System.setIn(new ByteArrayInputStream("12ab\n".getBytes(StandardCharsets.UTF_8)));
        try (MockedStatic<Playwright> playwrightStatic = mockStatic(Playwright.class)) {
            playwrightStatic.when(Playwright::create).thenReturn(playwright);
            assertThrows(BrowserAuthException.class, service::headlessCredentialsLogin);
        } finally {
            System.setIn(originalInput);
        }

        verify(verifyButton, times(0)).click();
        verify(context).close();
        verify(playwright).close();
    }

    @Test
    void interactiveLoginClosesWindowAfterSuccess() throws Exception {
        Playwright playwright = mock(Playwright.class);
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        stubLaunch(playwright, context, page);
        stubSuccessfulAuth(context, page);

        try (MockedStatic<Playwright> playwrightStatic = mockStatic(Playwright.class)) {
            playwrightStatic.when(Playwright::create).thenReturn(playwright);
            service.loginAndSaveAuth();
        }

        verify(context).close();
        verify(playwright).close();
    }

    @Test
    void headlessLoginClosesBrowserAfterFormFailure() {
        Playwright playwright = mock(Playwright.class);
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        Locator textboxes = mock(Locator.class);
        Locator email = mock(Locator.class);
        stubLaunch(playwright, context, page);
        when(page.getByRole(AriaRole.TEXTBOX)).thenReturn(textboxes);
        when(textboxes.first()).thenReturn(email);
        org.mockito.Mockito.doThrow(new RuntimeException("Form unavailable")).when(email).waitFor(any());

        try (MockedStatic<Playwright> playwrightStatic = mockStatic(Playwright.class)) {
            playwrightStatic.when(Playwright::create).thenReturn(playwright);
            assertThrows(BrowserAuthException.class, service::headlessCredentialsLogin);
        }

        verify(context).close();
        verify(playwright).close();
    }

    private void stubLaunch(Playwright playwright, BrowserContext context, Page page) {
        BrowserType browserType = mock(BrowserType.class);
        when(playwright.chromium()).thenReturn(browserType);
        when(browserType.launchPersistentContext(any(), any())).thenReturn(context);
        when(context.pages()).thenReturn(List.of(page));
    }

    private static void stubLoginForm(Page page, Locator email, Locator password, Locator submit) {
        Locator textboxes = mock(Locator.class);
        Locator checkboxes = mock(Locator.class);
        Locator checkbox = mock(Locator.class);
        when(page.getByRole(AriaRole.TEXTBOX)).thenReturn(textboxes);
        when(textboxes.first()).thenReturn(email);
        when(page.locator("input[type=\"password\"]")).thenReturn(password);
        when(page.locator("input[type=\"checkbox\"]")).thenReturn(checkboxes);
        when(checkboxes.first()).thenReturn(checkbox);
        when(checkbox.isVisible(any())).thenReturn(false);
        when(page.locator("[role=\"button\"].ds-button--primary")).thenReturn(submit);
    }

    private static void stubNoVerification(Page page) {
        Locator codeInput = mock(Locator.class);
        when(page.locator("input[autocomplete=\"one-time-code\"]")).thenReturn(codeInput);
        org.mockito.Mockito.doThrow(new RuntimeException("No verification")).when(codeInput).waitFor(any());
    }

    private static void stubVerification(
            Page page, Locator codeInput, Locator sendCode, Locator verifyButton) {
        when(page.locator("input[autocomplete=\"one-time-code\"]")).thenReturn(codeInput);
        when(page.locator("[role=\"button\"].ds-verify-code-input-countdown")).thenReturn(sendCode);
        when(page.locator("[role=\"button\"].ds-mfa-verification-submit")).thenReturn(verifyButton);
    }

    private static void stubSuccessfulAuth(BrowserContext context, Page page) {
        Response response = mock(Response.class);
        Request request = mock(Request.class);
        when(response.url()).thenReturn(BASE_URL + "/api/v0/user/info");
        when(response.status()).thenReturn(200);
        when(response.request()).thenReturn(request);
        when(request.headers()).thenReturn(Map.of("authorization", "Bearer 12345678901234567"));
        when(response.body()).thenReturn("{\"code\":0}".getBytes(StandardCharsets.UTF_8));
        doAnswer(invocation -> {
                    Consumer<Response> listener = invocation.getArgument(0);
                    listener.accept(response);
                    return null;
                })
                .when(context)
                .onResponse(any());
        stubSession(context, page);
    }

    private static void stubSession(BrowserContext context, Page page) {
        when(context.cookies(List.of(BASE_URL))).thenReturn(List.of(new Cookie("ds_session_id", "session")));
        when(page.evaluate(any(String.class))).thenReturn("token");
        when(page.url()).thenReturn(BASE_URL + "/");
    }

    private void invokeFillLoginForm(Page page) throws Exception {
        Method method = BrowserAuthService.class.getDeclaredMethod("fillLoginForm", Page.class);
        method.setAccessible(true);
        try {
            method.invoke(service, page);
        } catch (InvocationTargetException exc) {
            if (exc.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exc;
        }
    }
}
