"""Персистентность авторизации (порт хранения из src/auth.mjs).

Формат auth.json не меняется (version 1) — файл взаимозаменяем
с Node-версией приложения.
"""
from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from ..config import AUTH_DIR, AUTH_FILE, BASE_URL, BROWSER_PROFILE

AUTH_FORMAT_VERSION = 1
SESSION_COOKIE = "ds_session_id"


class AuthStorageError(Exception):
    """Ошибка чтения/валидации сохранённой авторизации."""


def normalize_token(input_token: Any) -> str:
    token = str(input_token or "").strip()
    if not token:
        return ""
    try:
        parsed = json.loads(token)
    except (json.JSONDecodeError, TypeError):
        return token
    if isinstance(parsed, str):
        return parsed.strip()
    if isinstance(parsed, dict) and isinstance(parsed.get("value"), str):
        return parsed["value"].strip()
    return token


def cookie_header_from_array(parsed: Any) -> str:
    if not isinstance(parsed, list):
        raise AuthStorageError("Cookie data must be a JSON array.")
    usable = [
        c
        for c in parsed
        if isinstance(c, dict) and c.get("name") and "value" in c
    ]
    if not any(c["name"] == SESSION_COOKIE for c in usable):
        raise AuthStorageError("Cookie file does not contain ds_session_id.")
    return "; ".join(f"{c['name']}={c['value']}" for c in usable)


def read_saved_auth(
    auth_file: Path | None = None,
) -> dict[str, str] | None:
    path = auth_file or AUTH_FILE
    if not path.exists():
        return None
    parsed = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(parsed, dict):
        return None
    token = normalize_token(parsed.get("userToken") or parsed.get("token") or "")
    cookie_header = cookie_header_from_array(parsed.get("cookies") or [])
    return {"token": token, "cookieHeader": cookie_header}


def write_saved_auth(*, cookies: list[dict], user_token: Any) -> None:
    AUTH_DIR.mkdir(parents=True, exist_ok=True)
    payload = {
        "version": AUTH_FORMAT_VERSION,
        "savedAt": datetime.now(timezone.utc).isoformat(),
        "baseUrl": BASE_URL,
        "profileDir": str(BROWSER_PROFILE),
        "userToken": user_token,
        "cookies": cookies,
    }
    AUTH_FILE.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    try:
        os.chmod(AUTH_FILE, 0o600)
    except OSError:
        pass


def import_cookies(cookies_file_path: str, user_token: str) -> dict[str, str]:
    """Ручной импорт cookies из JSON-файла + userToken (порт importCookies)."""
    path = Path(cookies_file_path)
    if not path.exists():
        raise AuthStorageError(f"Файл cookies не найден: {cookies_file_path}")

    try:
        cookies = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        raise AuthStorageError("Файл cookies должен быть валидным JSON.") from None

    if not isinstance(cookies, list):
        wrapped = cookies.get("cookies") if isinstance(cookies, dict) else None
        if isinstance(wrapped, list):
            cookies = wrapped
        else:
            raise AuthStorageError("Файл cookies должен быть массивом JSON-объектов.")

    token = normalize_token(user_token)
    if not token:
        raise AuthStorageError("Токен не может быть пустым.")

    usable = [c for c in cookies if isinstance(c, dict) and c.get("name") and "value" in c]
    if not any(c["name"] == SESSION_COOKIE for c in usable):
        raise AuthStorageError(
            "В файле cookies нет ds_session_id. Убедись, что экспортировал куки с chat.deepseek.com"
        )

    cookie_header = cookie_header_from_array(usable)
    write_saved_auth(cookies=usable, user_token=token)
    return {"token": token, "cookieHeader": cookie_header}


def print_manual_instructions() -> None:
    print(
        """
══════════════════════════════════════════════════
  Ручной экспорт сессии DeepSeek
══════════════════════════════════════════════════

  1. Открой Chrome и зайди на https://chat.deepseek.com
  2. Убедись что ты залогинен (должен быть интерфейс чата)
  3. Открой DevTools (F12 или Ctrl+Shift+I)
  4. Перейди на вкладку Application → Local Storage
     → https://chat.deepseek.com
  5. Найди ключ "userToken" и скопируй его значение целиком
  6. Перейди на вкладку Application → Cookies
     → https://chat.deepseek.com
  7. Экспортируй все куки в файл (или скопируй вручную)

  Формат cookies JSON:
  [
    {"name": "ds_session_id", "value": "...", "domain": "chat.deepseek.com", ...},
    {"name": "...", "value": "...", ...}
  ]

  Сохрани файл и выполни:
    deepseek-free-api --import cookies.json "<userToken>"
══════════════════════════════════════════════════
"""
    )
