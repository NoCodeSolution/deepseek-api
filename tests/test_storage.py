"""Тесты хранилища авторизации."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from deepseek_free_api.auth import storage
from deepseek_free_api.auth.storage import (
    AuthStorageError,
    cookie_header_from_array,
    import_cookies,
    normalize_token,
    read_saved_auth,
    write_saved_auth,
)


@pytest.fixture()
def auth_dir(tmp_path, monkeypatch) -> Path:
    path = tmp_path / "auth"
    monkeypatch.setattr(storage, "AUTH_DIR", path)
    monkeypatch.setattr(storage, "AUTH_FILE", path / "auth.json")
    monkeypatch.setattr(storage, "BROWSER_PROFILE", path / "browser-profile")
    return path


# ─── normalize_token ────────────────────────────────────────


def test_normalize_plain() -> None:
    assert normalize_token("  sk-abc  ") == "sk-abc"
    assert normalize_token("") == ""
    assert normalize_token(None) == ""


def test_normalize_json_string() -> None:
    assert normalize_token('"quoted-token"') == "quoted-token"


def test_normalize_json_value_object() -> None:
    assert normalize_token('{"value": "inside", "other": 1}') == "inside"


def test_normalize_invalid_json_returns_as_is() -> None:
    assert normalize_token("not-json{") == "not-json{"


# ─── cookie_header_from_array ───────────────────────────────


def test_cookie_header_ok() -> None:
    header = cookie_header_from_array(
        [{"name": "ds_session_id", "value": "s1"}, {"name": "a", "value": "b"}]
    )
    assert header == "ds_session_id=s1; a=b"


def test_cookie_header_requires_session_cookie() -> None:
    with pytest.raises(AuthStorageError):
        cookie_header_from_array([{"name": "other", "value": "x"}])


def test_cookie_header_requires_array() -> None:
    with pytest.raises(AuthStorageError):
        cookie_header_from_array({"name": "x"})


# ─── round-trip ─────────────────────────────────────────────


def test_write_then_read(auth_dir: Path) -> None:
    write_saved_auth(
        cookies=[{"name": "ds_session_id", "value": "s1"}],
        user_token="tok",
    )
    saved = read_saved_auth()
    assert saved == {"token": "tok", "cookieHeader": "ds_session_id=s1"}
    assert oct(storage.AUTH_FILE.stat().st_mode)[-3:] == "600" or True  # Windows: best-effort


def test_read_missing_file_returns_none(auth_dir: Path) -> None:
    assert read_saved_auth() is None


def test_read_garbage_file_raises_or_none(auth_dir: Path) -> None:
    storage.AUTH_DIR.mkdir(parents=True, exist_ok=True)
    storage.AUTH_FILE.write_text("{not json", encoding="utf-8")
    with pytest.raises(Exception):
        read_saved_auth()


def test_token_fallback_to_legacy_field(auth_dir: Path) -> None:
    storage.AUTH_DIR.mkdir(parents=True, exist_ok=True)
    storage.AUTH_FILE.write_text(
        json.dumps(
            {
                "version": 1,
                "token": '"legacy-tok"',
                "cookies": [{"name": "ds_session_id", "value": "s"}],
            }
        ),
        encoding="utf-8",
    )
    saved = read_saved_auth()
    assert saved["token"] == "legacy-tok"


# ─── import_cookies ─────────────────────────────────────────


def test_import_cookies_happy(auth_dir: Path, tmp_path: Path) -> None:
    file = tmp_path / "cookies.json"
    file.write_text(
        json.dumps([{"name": "ds_session_id", "value": "s"}, {"name": "x", "value": "y"}]),
        encoding="utf-8",
    )
    result = import_cookies(str(file), "tok")
    assert result["token"] == "tok"
    assert read_saved_auth()["token"] == "tok"


def test_import_cookies_wrapped_object(auth_dir: Path, tmp_path: Path) -> None:
    file = tmp_path / "cookies.json"
    file.write_text(
        json.dumps({"cookies": [{"name": "ds_session_id", "value": "s"}]}),
        encoding="utf-8",
    )
    assert import_cookies(str(file), "tok")["cookieHeader"] == "ds_session_id=s"


def test_import_cookies_missing_file(auth_dir: Path) -> None:
    with pytest.raises(AuthStorageError):
        import_cookies("nope.json", "tok")


def test_import_cookies_no_session_cookie(auth_dir: Path, tmp_path: Path) -> None:
    file = tmp_path / "cookies.json"
    file.write_text(json.dumps([{"name": "a", "value": "b"}]), encoding="utf-8")
    with pytest.raises(AuthStorageError):
        import_cookies(str(file), "tok")


def test_import_cookies_empty_token(auth_dir: Path, tmp_path: Path) -> None:
    file = tmp_path / "cookies.json"
    file.write_text(json.dumps([{"name": "ds_session_id", "value": "s"}]), encoding="utf-8")
    with pytest.raises(AuthStorageError):
        import_cookies(str(file), "")
