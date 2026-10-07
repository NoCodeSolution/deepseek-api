"""Браузерная авторизация через Playwright (порт src/auth.mjs).

Режимы: интерактивный логин (окно), тихий refresh из профиля,
подключение к запущенному Chrome через CDP.
"""
from __future__ import annotations

import asyncio
import logging
import sys
from pathlib import Path
from typing import Any

from playwright.async_api import (
    BrowserContext,
    Playwright,
    Response,
    async_playwright,
)

from ..config import BASE_URL, BROWSER_PROFILE
from .storage import (
    cookie_header_from_array,
    normalize_token,
    write_saved_auth,
)

logger = logging.getLogger(__name__)

LAUNCH_ARGS = ["--disable-blink-features=AutomationControlled"]
LOGIN_TIMEOUT_S = 300
API_PATH_MARKER = "/api/v0/"


class BrowserAuthError(Exception):
    """Ошибка браузерной авторизации."""


async def _launch_persistent(playwright: Playwright, headless: bool) -> BrowserContext:
    profile_dir = Path(BROWSER_PROFILE)
    profile_dir.mkdir(parents=True, exist_ok=True)

    async def try_launch() -> BrowserContext:
        try:
            return await playwright.chromium.launch_persistent_context(
                str(profile_dir),
                headless=headless,
                viewport=None,
                args=LAUNCH_ARGS,
                channel="chrome",
            )
        except Exception as chrome_exc:
            try:
                return await playwright.chromium.launch_persistent_context(
                    str(profile_dir),
                    headless=headless,
                    viewport=None,
                    args=LAUNCH_ARGS,
                )
            except Exception as chromium_exc:
                raise BrowserAuthError(
                    f"Chrome: {chrome_exc}. Chromium: {chromium_exc}"
                ) from chromium_exc

    try:
        return await try_launch()
    except BrowserAuthError as error:
        if "SingletonLock" not in str(error):
            raise BrowserAuthError(
                "Не удалось открыть браузер. Установи Google Chrome или выполни "
                f'"playwright install chromium". {error}'
            ) from error
        for name in ("SingletonLock", "SingletonCookie", "SingletonSocket"):
            try:
                (profile_dir / name).unlink()
            except OSError:
                pass
        try:
            return await try_launch()
        except BrowserAuthError as retry_error:
            raise BrowserAuthError(
                f"Не удалось открыть браузер: {retry_error}"
            ) from retry_error


async def _read_session_state(context: BrowserContext) -> tuple[list[dict], str]:
    cookies = await context.cookies(BASE_URL)
    pages = context.pages
    token = ""
    if pages:
        raw = await pages[0].evaluate(
            "() => { try { return localStorage.getItem('userToken'); } catch { return null; } }"
        )
        token = normalize_token(raw or "")
    return cookies, token


def _validate_session(cookies: list[dict], token: str) -> None:
    if not token:
        raise BrowserAuthError("В localStorage нет userToken. Логин не завершён.")
    if not any(c.get("name") == "ds_session_id" for c in cookies):
        raise BrowserAuthError("В куках нет ds_session_id. Логин не завершён.")


async def _wait_for_auth_api_call(
    context: BrowserContext, timeout_s: int = LOGIN_TIMEOUT_S
) -> None:
    """Ждёт успешный авторизованный вызов API — признак завершённого входа."""
    done = asyncio.Event()

    def on_response(response: Response) -> None:
        if done.is_set():
            return
        try:
            url = response.url
            if API_PATH_MARKER not in url or response.status != 200:
                return
            auth = response.request.headers.get("authorization", "")
            if not auth.lower().startswith("bearer ") or len(auth) < 17:
                return
            # Даём ответу «осесть» (settle), как в Node-версии
            asyncio.ensure_future(_settle_and_done(response, done))
        except Exception:  # noqa: BLE001 - слушатель не должен падать
            pass

    async def _settle_and_done(response: Response, event: asyncio.Event) -> None:
        try:
            body = await response.json()
        except Exception:
            return
        if isinstance(body, dict) and body.get("code", 0) != 0:
            return
        await asyncio.sleep(0.8)
        event.set()

    context.on("response", on_response)
    try:
        await asyncio.wait_for(done.wait(), timeout=timeout_s)
    except asyncio.TimeoutError:
        raise BrowserAuthError(
            f"Таймаут входа {timeout_s}с. Залогинься в окне DeepSeek."
        ) from None


async def login_and_save_auth() -> dict[str, str]:
    """Интерактивный логин: открывает окно, ждёт входа, сохраняет сессию."""
    async with async_playwright() as playwright:
        context = await _launch_persistent(playwright, headless=False)
        try:
            page = context.pages[0] if context.pages else await context.new_page()
            await page.goto(BASE_URL, wait_until="domcontentloaded")
            print(
                "\n🔓 Откроется окно DeepSeek. Залогинься там любым способом.\n"
                "   Окно закроется автоматически после успешного входа.\n"
            )
            await _wait_for_auth_api_call(context)
            cookies, token = await _read_session_state(context)
            _validate_session(cookies, token)
            write_saved_auth(cookies=cookies, user_token=token)
        finally:
            await context.close()
    return {"token": token, "cookieHeader": cookie_header_from_array(cookies)}


async def refresh_auth_from_profile() -> dict[str, str] | None:
    """Тихий refresh: headless-профиль → свежие token + cookies, если сессия жива."""
    async with async_playwright() as playwright:
        try:
            context = await _launch_persistent(playwright, headless=True)
        except BrowserAuthError as exc:
            logger.debug("Silent refresh: браузер недоступен: %s", exc)
            return None
        try:
            page = context.pages[0] if context.pages else await context.new_page()
            try:
                await page.goto(BASE_URL, wait_until="domcontentloaded")
            except Exception:  # noqa: BLE001 - страница может не догрузиться
                pass
            cookies, token = await _read_session_state(context)
            if not token or not any(
                c.get("name") == "ds_session_id" for c in cookies
            ):
                return None
            write_saved_auth(cookies=cookies, user_token=token)
            return {
                "token": token,
                "cookieHeader": cookie_header_from_array(cookies),
            }
        finally:
            await context.close()


async def clear_profile_session() -> None:
    async with async_playwright() as playwright:
        context = await _launch_persistent(playwright, headless=True)
        try:
            await context.clear_cookies()
        finally:
            await context.close()


async def connect_to_running_chrome(cdp_port: int = 9222) -> dict[str, str]:
    """Забирает сессию из запущенного Chrome с remote-debugging-port."""
    cdp_url = f"http://127.0.0.1:{cdp_port}"
    print(f"\n🔗 Подключаюсь к Chrome на {cdp_url}...")
    async with async_playwright() as playwright:
        try:
            browser = await playwright.chromium.connect_over_cdp(cdp_url)
        except Exception as exc:
            raise BrowserAuthError(
                f"Не удалось подключиться к Chrome.\n"
                f"1. Закрой весь Chrome\n"
                f"2. Запусти его заново этой командой в терминале:\n"
                f'   chrome --remote-debugging-port={cdp_port}\n'
                f"   (Windows: \"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\" "
                f"--remote-debugging-port={cdp_port})\n"
            ) from exc

        try:
            context = browser.contexts[0] if browser.contexts else await browser.new_context()
            page = None
            for ctx in browser.contexts:
                for candidate in ctx.pages:
                    if candidate.url.startswith(BASE_URL):
                        page = candidate
                        break
                if page:
                    break
            if page is None:
                page = await context.new_page()

            await page.goto(BASE_URL, wait_until="domcontentloaded", timeout=15000)
            raw_token = await page.evaluate(
                "() => { try { return localStorage.getItem('userToken'); } catch { return null; } }"
            )
            token = normalize_token(raw_token or "")
            cookies = await context.cookies(BASE_URL)

            if not token:
                print(
                    "ℹ️ Ты не залогинен в DeepSeek. Открываю страницу — зайди в аккаунт.\n"
                    "   После входа нажми Enter в этом терминале."
                )
                await page.goto(f"{BASE_URL}/", wait_until="domcontentloaded")
                await asyncio.get_event_loop().run_in_executor(None, sys.stdin.readline)
                raw_token = await page.evaluate(
                    "() => { try { return localStorage.getItem('userToken'); } catch { return null; } }"
                )
                token = normalize_token(raw_token or "")
                cookies = await context.cookies(BASE_URL)
                if not token or not any(
                    c.get("name") == "ds_session_id" for c in cookies
                ):
                    raise BrowserAuthError(
                        "Логин не подтверждён. Нет userToken или ds_session_id."
                    )
                write_saved_auth(cookies=cookies, user_token=token)
                return {
                    "token": token,
                    "cookieHeader": cookie_header_from_array(cookies),
                }

            write_saved_auth(cookies=cookies, user_token=token)
            return {
                "token": token,
                "cookieHeader": cookie_header_from_array(cookies),
            }
        finally:
            await browser.close()
