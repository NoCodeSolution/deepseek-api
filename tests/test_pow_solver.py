"""Тесты PoW-солвера (офлайн-детерминированные + live с --live)."""
from __future__ import annotations

import httpx
import pytest

from deepseek_free_api.auth.storage import read_saved_auth
from deepseek_free_api.ds_client import build_headers
from deepseek_free_api.pow_solver import PowError, solve_pow


def test_unsupported_algorithm() -> None:
    with pytest.raises(PowError):
        solve_pow(
            {"algorithm": "Other", "challenge": "aa", "salt": "1", "difficulty": 1, "expire_at": 1}
        )


def test_missing_expire_at() -> None:
    with pytest.raises(PowError):
        solve_pow(
            {"algorithm": "DeepSeekHashV1", "challenge": "aa", "salt": "1", "difficulty": 1}
        )


def test_odd_challenge_is_pow_error_not_crash() -> None:
    # нечётная длина челленджа → wasm status 0 → PowError
    with pytest.raises(PowError):
        solve_pow(
            {
                "algorithm": "DeepSeekHashV1",
                "challenge": "abc",
                "salt": "123",
                "difficulty": 1,
                "expire_at": 1893456000,
            }
        )


def test_string_fields_coerced() -> None:
    with pytest.raises(PowError):
        solve_pow(
            {
                "algorithm": "DeepSeekHashV1",
                "challenge": "abc",
                "salt": "123",
                "difficulty": "5",
                "expire_at": "1893456000",
            }
        )


@pytest.mark.live
def test_live_real_challenge() -> None:
    """Решает реальный челлендж живого аккаунта (нужен --live и валидный auth.json)."""
    saved = read_saved_auth()
    assert saved, "нет auth.json — выполни deepseek-free-api --login"
    response = httpx.post(
        "https://chat.deepseek.com/api/v0/chat/create_pow_challenge",
        headers=build_headers(saved["cookieHeader"], saved["token"]),
        json={"target_path": "/api/v0/chat/completion"},
        timeout=30,
    )
    challenge = response.json()["data"]["biz_data"]["challenge"]
    answer = solve_pow(challenge)
    assert isinstance(answer, int)
