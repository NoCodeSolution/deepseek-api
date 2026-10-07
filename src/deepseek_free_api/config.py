"""Константы и пути приложения.

Настройки — через переменные окружения. Дополнительно поддерживается `.env`
в корне проекта (формат KEY=VALUE): значения из реального окружения имеют
приоритет над файлом.
"""
from __future__ import annotations

import os
from pathlib import Path


def _load_dotenv() -> None:
    """Минимальный .env-загрузчик без зависимостей. Не перезаписывает env."""
    env_file = Path.cwd() / ".env"
    try:
        lines = env_file.read_text(encoding="utf-8").splitlines()
    except OSError:
        return
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


_load_dotenv()

# Диагностика: логировать тело входящих запросов, если ответ 422
LOG_REQUEST_BODIES = os.environ.get("LOG_REQUEST_BODIES", "") == "1"

BASE_URL = "https://chat.deepseek.com"
APP_VERSION = "2.0.0"
COMPLETION_PATH = "/api/v0/chat/completion"
DEEPSEEK_SHA3_WASM = (
    "https://fe-static.deepseek.com/chat/static/sha3_wasm_bg.7b9ca65ddd.wasm"
)

AUTH_DIR = Path(
    os.environ.get("DEEPSEEK_FREE_AUTH_DIR", Path.home() / ".deepseek-free-api")
)
AUTH_FILE = AUTH_DIR / "auth.json"
BROWSER_PROFILE = AUTH_DIR / "browser-profile"
WASM_CACHE_FILE = AUTH_DIR / "sha3_wasm_bg.wasm"

DEFAULT_PORT = 18632
DEFAULT_HOST = "0.0.0.0"

# Модель по умолчанию (без размышлений), если в запросе модель не задана
# или неизвестна.
DEFAULT_MODEL = os.environ.get("DEFAULT_MODEL", "deepseek-chat")

# Формат ответа /v1/chat/completions (non-stream):
#   "text"   — голый текст ответа модели (Content-Type: text/plain)
#   "openai" — полный OpenAI chat.completion JSON
RESPONSE_FORMAT = os.environ.get("RESPONSE_FORMAT", "text")

# Креды для headless авто-логина (заполняется форма DeepSeek без окна).
DS_EMAIL = os.environ.get("DS_EMAIL", "")
DS_PASSWORD = os.environ.get("DS_PASSWORD", "")

# Таймаут headless-логина по кредам, секунд.
HEADLESS_LOGIN_TIMEOUT_S = float(os.environ.get("HEADLESS_LOGIN_TIMEOUT_S", "60"))
