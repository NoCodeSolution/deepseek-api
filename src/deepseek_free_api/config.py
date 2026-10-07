"""Константы и пути приложения."""
from __future__ import annotations

import os
from pathlib import Path

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
DEFAULT_HOST = "127.0.0.1"
