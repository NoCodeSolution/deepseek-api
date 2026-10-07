"""AuthManager: состояние авторизации, тихий refresh, эскалация до интерактивного входа.

Стратегия (по плану):
- при старте: auth.json → применить → тихий refresh из browser-profile;
- на auth-ошибке DeepSeek: single-flight silent refresh → повтор запроса;
  если снова ошибка — интерактивное окно логина → повтор;
- фоновый тихий refresh каждые REFRESH_INTERVAL_H часов.
"""
from __future__ import annotations

import asyncio
import enum
import logging
import os
from datetime import datetime, timezone
from typing import Awaitable, Callable, TypeVar

from .storage import AuthStorageError, read_saved_auth
from . import browser

logger = logging.getLogger(__name__)

T = TypeVar("T")

REFRESH_INTERVAL_H = float(os.environ.get("REFRESH_INTERVAL_H", "6"))
INTERACTIVE_LOGIN = os.environ.get("INTERACTIVE_LOGIN", "1") != "0"


class AuthState(enum.Enum):
    LOADING = "loading"
    READY = "ready"
    REFRESHING = "refreshing"
    EXPIRED = "expired"


class AuthManager:
    def __init__(
        self,
        *,
        interactive: bool = INTERACTIVE_LOGIN,
        refresh_interval_h: float = REFRESH_INTERVAL_H,
    ) -> None:
        self._state = AuthState.LOADING
        self._cookie_header = ""
        self._token = ""
        self._saved_at: datetime | None = None
        self._lock = asyncio.Lock()
        self._interactive = interactive
        self._refresh_interval_h = refresh_interval_h
        self._timer_task: asyncio.Task | None = None
        self._last_error = ""
        self._generation = 0

    # ─── Состояние ────────────────────────────────────────────

    @property
    def state(self) -> AuthState:
        return self._state

    @property
    def credentials(self) -> dict[str, str] | None:
        if not self._token:
            return None
        return {"cookieHeader": self._cookie_header, "token": self._token}

    @property
    def status_dict(self) -> dict:
        return {
            "state": self._state.value,
            "has_token": bool(self._token),
            "token_saved_at": self._saved_at.isoformat() if self._saved_at else None,
            "interactive_available": self._interactive,
            "last_error": self._last_error,
        }

    def _apply(self, creds: dict[str, str]) -> None:
        self._cookie_header = creds["cookieHeader"]
        self._token = creds["token"]
        self._state = AuthState.READY
        self._saved_at = datetime.now(timezone.utc)
        self._last_error = ""

    def _drop(self, error: str) -> None:
        self._token = ""
        self._cookie_header = ""
        self._state = AuthState.EXPIRED
        self._last_error = error

    # ─── Жизненный цикл ───────────────────────────────────────

    async def startup(self) -> None:
        """Загрузка сохранённой сессии + тихий refresh + фоновый таймер."""
        try:
            saved = read_saved_auth()
        except (AuthStorageError, OSError) as exc:
            logger.warning("auth.json не прочитан: %s", exc)
            self._drop(str(exc))
            saved = None

        if saved:
            self._apply(saved)
            logger.info("Загружена сохранённая авторизация")
            refreshed = await self._silent_refresh()
            if refreshed:
                logger.info("Токен обновлён из профиля при старте")
        else:
            self._drop("auth.json не найден")
            logger.warning(
                "Нет сохранённой авторизации. Выполни: deepseek-free-api --login"
            )

        self._timer_task = asyncio.create_task(self._refresh_loop())

    async def shutdown(self) -> None:
        if self._timer_task:
            self._timer_task.cancel()
            await asyncio.gather(self._timer_task, return_exceptions=True)
            self._timer_task = None

    async def _refresh_loop(self) -> None:
        while True:
            await asyncio.sleep(self._refresh_interval_h * 3600)
            try:
                if await self._silent_refresh():
                    logger.info("Фоновый refresh: токен обновлён")
            except Exception as exc:  # noqa: BLE001 - таймер не должен умирать
                logger.warning("Фоновый refresh не удался: %s", exc)

    # ─── Refresh ──────────────────────────────────────────────

    async def _silent_refresh(self) -> bool:
        creds = await browser.refresh_auth_from_profile()
        if creds:
            self._apply(creds)
            return True
        return False

    async def refresh(self, *, allow_interactive: bool = True) -> bool:
        """Single-flight refresh: silent → интерактивная эскалация.

        Повторные вызовы подряд (пачка параллельных запросов с протухшим
        токеном) не запускают браузер заново: увидев чужое успешное
        обновление, возвращаем True сразу. Управляет поколением _generation.
        """
        seen_generation = self._generation
        async with self._lock:
            if self._token and self._generation != seen_generation:
                return True
            self._state = AuthState.REFRESHING
            try:
                if await self._silent_refresh():
                    self._generation += 1
                    logger.info("Авторизация обновлена (silent refresh)")
                    return True
                logger.warning("Silent refresh не помог, сессия профиля мертва")
                if allow_interactive and self._interactive:
                    logger.warning("Открываю окно логина...")
                    try:
                        creds = await browser.login_and_save_auth()
                    except browser.BrowserAuthError as exc:
                        self._drop(str(exc))
                        return False
                    self._apply(creds)
                    self._generation += 1
                    logger.info("Авторизация восстановлена интерактивным входом")
                    return True
                self._drop("silent refresh не удался, интерактивный вход отключён")
                return False
            except Exception as exc:  # noqa: BLE001
                logger.exception("Refresh упал")
                self._drop(str(exc))
                return False

    async def login_now(self) -> bool:
        """Принудительный интерактивный вход (эндпоинт /v1/auth/login)."""
        async with self._lock:
            self._state = AuthState.REFRESHING
            try:
                creds = await browser.login_and_save_auth()
            except browser.BrowserAuthError as exc:
                self._drop(str(exc))
                return False
            self._apply(creds)
            self._generation += 1
            return True

    # ─── Retry-обёртка ────────────────────────────────────────

    async def call_with_refresh(
        self,
        factory: Callable[[], Awaitable[T]],
        *,
        allow_interactive: bool = True,
    ) -> T:
        """Выполняет factory(); при AuthRequiredError — refresh и один повтор."""
        from ..ds_client import AuthRequiredError

        try:
            return await factory()
        except AuthRequiredError:
            logger.warning("Auth error от DeepSeek, пробуем refresh")
            if await self.refresh(allow_interactive=allow_interactive):
                return await factory()
            raise
