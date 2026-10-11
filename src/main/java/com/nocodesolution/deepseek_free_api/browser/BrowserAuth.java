package com.nocodesolution.deepseek_free_api.browser;

import com.nocodesolution.deepseek_free_api.services.Credentials;

/** Браузерная авторизация. Реализация — Playwright (синхронный API). */
public interface BrowserAuth {

    /** Тихий refresh из профиля; null — сессия профиля мертва или браузер недоступен. */
    Credentials refreshFromProfile() throws BrowserAuthException;

    /** Headless-логин по кредам; сохраняет auth.json. */
    Credentials headlessCredentialsLogin() throws BrowserAuthException;

    /** Интерактивное окно логина; сохраняет auth.json. */
    Credentials loginAndSaveAuth() throws BrowserAuthException;

    /** Очистить куки профиля. */
    void clearProfileSession() throws BrowserAuthException;

    /** Забрать сессию из запущенного Chrome с remote-debugging-port. */
    Credentials connectToRunningChrome(int cdpPort) throws BrowserAuthException;
}
