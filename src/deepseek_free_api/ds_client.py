"""Клиент DeepSeek web API (порт src/client.mjs + src/headers.mjs)."""
from __future__ import annotations

import base64
import json
import logging
import os
import time
from typing import Any, Callable

import httpx

from .config import APP_VERSION, BASE_URL, COMPLETION_PATH
from .pow_solver import solve_pow
from .sse import stream_sse

logger = logging.getLogger(__name__)

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
)
AUTH_ERROR_CODES = (40002, 40003)

# Endpoint удаления чата не документирован; подтверждается вручную (фаза 7).
# При смене API правится через env без пересборки.
DELETE_SESSION_PATH = os.environ.get(
    "DS_CHAT_DELETE_PATH", "/api/v0/chat_session/delete"
)


class DeepSeekError(Exception):
    """Базовая ошибка DeepSeek API."""


class AuthRequiredError(DeepSeekError):
    """401/403 или бизнес-код 40002/40003 — нужна авторизация."""


class RateLimitError(DeepSeekError):
    """429 — лимит запросов DeepSeek."""


class UpstreamError(DeepSeekError):
    """Прочая ошибка upstream."""


def build_headers(cookie_header: str, token: str) -> dict[str, str]:
    local = time.localtime()
    tz_offset = getattr(local, "tm_gmtoff", None)
    if tz_offset is None:
        tz_offset = -time.timezone
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "*/*",
        "Accept-Language": "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
        "Content-Type": "application/json",
        "Origin": BASE_URL,
        "Referer": f"{BASE_URL}/",
        "Cookie": cookie_header,
        "X-App-Version": APP_VERSION,
        "x-client-platform": "web",
        "x-client-version": APP_VERSION,
        "x-client-locale": "ru",
        "x-client-timezone-offset": str(tz_offset),
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


def _biz_data(data: dict[str, Any]) -> dict[str, Any]:
    return (data.get("data") or {}).get("biz_data") or {}


class DeepSeekClient:
    def __init__(self, cookie_header: str, token: str, debug: bool = False):
        self._headers = build_headers(cookie_header, token)
        self.debug = debug
        self._http = httpx.AsyncClient(
            base_url=BASE_URL, headers=self._headers, timeout=None
        )

    async def aclose(self) -> None:
        await self._http.aclose()

    async def _request(
        self, path: str, *, method: str = "GET", body: dict | None = None
    ) -> dict[str, Any]:
        try:
            response = await self._http.request(method, path, json=body)
        except httpx.HTTPError as exc:
            raise UpstreamError(f"Network error at {path}: {exc}") from exc

        try:
            data = response.json()
        except json.JSONDecodeError:
            if response.status_code in (401, 403):
                raise AuthRequiredError(f"Auth required: HTTP {response.status_code}")
            raise UpstreamError(
                f"Expected JSON from {path}, got HTTP {response.status_code}: "
                f"{response.text[:180]}"
            )

        code = data.get("code") if isinstance(data, dict) else None
        if response.status_code in (401, 403) or code in AUTH_ERROR_CODES:
            raise AuthRequiredError(f"Auth required: code {code or response.status_code}")
        if response.status_code == 429:
            raise RateLimitError(f"Rate limited: HTTP 429: {str(data)[:200]}")
        if not 200 <= response.status_code < 300 or (code is not None and code != 0):
            msg = data.get("msg", "") if isinstance(data, dict) else ""
            raise UpstreamError(
                f"DeepSeek API error at {path}: HTTP {response.status_code}, "
                f"code {code}, msg {msg}"
            )
        return data if isinstance(data, dict) else {}

    async def create_session(self) -> str:
        data = await self._request("/api/v0/chat_session/create", method="POST", body={})
        session = _biz_data(data).get("chat_session") or {}
        session_id = session.get("id")
        if not session_id:
            raise UpstreamError(
                "Cannot read chat session id: "
                f"{json.dumps(data, ensure_ascii=False)[:300]}"
            )
        return str(session_id)

    async def create_pow_header(self, target_path: str) -> str:
        data = await self._request(
            "/api/v0/chat/create_pow_challenge",
            method="POST",
            body={"target_path": target_path},
        )
        challenge = _biz_data(data).get("challenge")
        if not challenge:
            raise UpstreamError(
                "Cannot read PoW challenge: "
                f"{json.dumps(data, ensure_ascii=False)[:300]}"
            )
        answer = solve_pow(challenge)
        payload = {
            "algorithm": challenge["algorithm"],
            "challenge": challenge["challenge"],
            "salt": challenge["salt"],
            "answer": answer,
            "signature": challenge["signature"],
            "target_path": target_path,
        }
        raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        return base64.b64encode(raw).decode("ascii")

    async def complete(
        self,
        *,
        session_id: str,
        prompt: str,
        parent_message_id: Any = None,
        model_type: str | None = None,
        thinking_enabled: bool = False,
        search_enabled: bool = False,
        on_delta: Callable[[str, str], None] | None = None,
    ) -> tuple[int | None, str, str]:
        """Шлёт completion. Возвращает (last_message_id, text, reasoning)."""
        pow_header = await self.create_pow_header(COMPLETION_PATH)
        body = {
            "chat_session_id": session_id,
            "parent_message_id": parent_message_id,
            "model_type": model_type,
            "preempt": False,
            "prompt": prompt,
            "ref_file_ids": [],
            "thinking_enabled": thinking_enabled,
            "search_enabled": search_enabled,
        }
        headers = {"X-DS-PoW-Response": pow_header}
        try:
            async with self._http.stream(
                "POST", COMPLETION_PATH, json=body, headers=headers
            ) as response:
                content_type = response.headers.get("content-type", "")
                if not 200 <= response.status_code < 300 or "text/event-stream" not in content_type:
                    text = (await response.aread()).decode("utf-8", errors="replace")
                    if response.status_code in (401, 403):
                        raise AuthRequiredError("Auth required during completion")
                    if response.status_code == 429:
                        raise RateLimitError(f"Rate limited: HTTP 429: {text[:200]}")
                    try:
                        parsed = json.loads(text)
                    except json.JSONDecodeError:
                        parsed = None
                    if isinstance(parsed, dict) and parsed.get("code") in AUTH_ERROR_CODES:
                        raise AuthRequiredError("Auth required during completion")
                    raise UpstreamError(
                        f"Completion failed: HTTP {response.status_code}: {text[:1000]}"
                    )
                return await stream_sse(response, on_delta=on_delta, debug=self.debug)
        except httpx.HTTPError as exc:
            raise UpstreamError(f"Completion request failed: {exc}") from exc

    async def delete_session(self, session_id: str) -> bool:
        """Удаляет чат. Best-effort: исключения не летят наружу, только лог."""
        try:
            await self._request(
                DELETE_SESSION_PATH,
                method="POST",
                body={"chat_session_id": session_id},
            )
            logger.info("Чат %s удалён", session_id)
            return True
        except DeepSeekError as exc:
            logger.warning("Не удалось удалить чат %s: %s", session_id, exc)
            return False
