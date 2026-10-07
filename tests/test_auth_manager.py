"""Тесты AuthManager (state machine, single-flight, эскалация)."""
from __future__ import annotations

import asyncio
import json
from pathlib import Path

import pytest

from deepseek_free_api.auth import browser, manager, storage
from deepseek_free_api.auth.manager import AuthManager, AuthState
from deepseek_free_api.ds_client import AuthRequiredError


@pytest.fixture()
def auth_dir(tmp_path, monkeypatch) -> Path:
    path = tmp_path / "auth"
    monkeypatch.setattr(storage, "AUTH_DIR", path)
    monkeypatch.setattr(storage, "AUTH_FILE", path / "auth.json")
    monkeypatch.setattr(storage, "BROWSER_PROFILE", path / "browser-profile")
    monkeypatch.setattr(browser, "BROWSER_PROFILE", path / "browser-profile")
    return path


def write_auth(path: Path, token: str = "saved-tok") -> None:
    path.mkdir(parents=True, exist_ok=True)
    (path / "auth.json").write_text(
        json.dumps(
            {
                "version": 1,
                "userToken": token,
                "cookies": [{"name": "ds_session_id", "value": "s"}],
            }
        ),
        encoding="utf-8",
    )


def silent_ok(creds=None):
    async def _impl():
        return creds or {"token": "fresh-tok", "cookieHeader": "ds_session_id=s"}

    return _impl


def silent_fail():
    async def _impl():
        return None

    return _impl


def login_ok(token="login-tok"):
    async def _impl():
        return {"token": token, "cookieHeader": "ds_session_id=s"}

    return _impl


def login_fail():
    async def _impl():
        raise browser.BrowserAuthError("пользователь закрыл окно")

    return _impl


def headless_ok(token="headless-tok"):
    async def _impl():
        return {"token": token, "cookieHeader": "ds_session_id=s"}

    return _impl


def headless_fail():
    async def _impl():
        raise browser.BrowserAuthError("капча")

    return _impl


def set_creds(monkeypatch, on: bool = True) -> None:
    monkeypatch.setattr(manager.config, "DS_EMAIL", "a@b.c" if on else "")
    monkeypatch.setattr(manager.config, "DS_PASSWORD", "pw" if on else "")


@pytest.fixture(autouse=True)
def _no_env_creds(monkeypatch) -> None:
    """Гасим DS_EMAIL/DS_PASSWORD из окружения разработчика/CI."""
    set_creds(monkeypatch, on=False)


async def test_refresh_uses_headless_creds(monkeypatch, auth_dir) -> None:
    """Silent fail → headless по кредам успешен → интерактивное окно не нужно."""
    write_auth(auth_dir)
    set_creds(monkeypatch)
    monkeypatch.setattr(browser, "refresh_auth_from_profile", silent_fail())
    monkeypatch.setattr(browser, "headless_credentials_login", headless_ok())
    monkeypatch.setattr(browser, "login_and_save_auth", login_fail())
    m = AuthManager(interactive=True, refresh_interval_h=999)
    await m.startup()
    await m.shutdown()
    assert await m.refresh() is True
    assert m.state == AuthState.READY
    assert m.credentials["token"] == "headless-tok"


async def test_refresh_headless_fail_falls_back_to_window(monkeypatch, auth_dir) -> None:
    """Headless по кредам упал (капча) → эскалация в интерактивное окно."""
    write_auth(auth_dir)
    set_creds(monkeypatch)
    monkeypatch.setattr(browser, "refresh_auth_from_profile", silent_fail())
    monkeypatch.setattr(browser, "headless_credentials_login", headless_fail())
    monkeypatch.setattr(browser, "login_and_save_auth", login_ok())
    m = AuthManager(interactive=True, refresh_interval_h=999)
    await m.startup()
    await m.shutdown()
    assert await m.refresh() is True
    assert m.credentials["token"] == "login-tok"


async def test_refresh_skips_headless_without_creds(monkeypatch, auth_dir) -> None:
    """Без DS_EMAIL/DS_PASSWORD headless-шаг не вызывается."""
    write_auth(auth_dir)
    set_creds(monkeypatch, on=False)
    calls = 0

    async def counting():
        nonlocal calls
        calls += 1
        return {"token": "t", "cookieHeader": "ds_session_id=s"}

    monkeypatch.setattr(browser, "refresh_auth_from_profile", silent_fail())
    monkeypatch.setattr(browser, "headless_credentials_login", counting)
    m = AuthManager(interactive=False, refresh_interval_h=999)
    await m.startup()
    await m.shutdown()
    assert await m.refresh(allow_interactive=False) is False
    assert calls == 0


async def test_startup_headless_login_without_auth_json(monkeypatch, auth_dir) -> None:
    """Нет auth.json + заданы креды → silent не помог → headless-логин на старте."""
    set_creds(monkeypatch)
    monkeypatch.setattr(browser, "refresh_auth_from_profile", silent_fail())
    monkeypatch.setattr(browser, "headless_credentials_login", headless_ok())
    m = AuthManager(interactive=False, refresh_interval_h=999)
    await m.startup()
    await m.shutdown()
    assert m.state == AuthState.READY
    assert m.credentials["token"] == "headless-tok"


async def test_startup_no_auth_expired(monkeypatch, auth_dir) -> None:
    monkeypatch.setattr(browser, "refresh_auth_from_profile", silent_fail())
    m = AuthManager(interactive=False, refresh_interval_h=999)
    await m.startup()
    await m.shutdown()
    assert m.state == AuthState.EXPIRED
    assert m.credentials is None


async def test_startup_saved_plus_silent_refresh(monkeypatch, auth_dir) -> None:
    write_auth(auth_dir)
    monkeypatch.setattr(browser, "refresh_auth_from_profile", silent_ok())
    m = AuthManager(interactive=False, refresh_interval_h=999)
    await m.startup()
    await m.shutdown()
    assert m.state == AuthState.READY
    assert m.credentials["token"] == "fresh-tok"


async def test_refresh_silent_success(monkeypatch, auth_dir) -> None:
    write_auth(auth_dir)
    calls = 0

    async def counting():
        nonlocal calls
        calls += 1
        return {"token": "t", "cookieHeader": "ds_session_id=s"}

    monkeypatch.setattr(browser, "refresh_auth_from_profile", counting)
    m = AuthManager(interactive=False, refresh_interval_h=999)
    await m.startup()
    await m.shutdown()
    assert await m.refresh(allow_interactive=False) is True
    assert calls == 2  # startup + refresh


async def test_refresh_escalates_to_interactive(monkeypatch, auth_dir) -> None:
    write_auth(auth_dir)
    monkeypatch.setattr(browser, "refresh_auth_from_profile", silent_fail())
    monkeypatch.setattr(browser, "login_and_save_auth", login_ok())
    m = AuthManager(interactive=True, refresh_interval_h=999)
    await m.startup()
    await m.shutdown()
    assert await m.refresh() is True
    assert m.state == AuthState.READY
    assert m.credentials["token"] == "login-tok"


async def test_refresh_no_interactive_drops(monkeypatch, auth_dir) -> None:
    write_auth(auth_dir)
    monkeypatch.setattr(browser, "refresh_auth_from_profile", silent_fail())
    m = AuthManager(interactive=False, refresh_interval_h=999)
    await m.startup()
    await m.shutdown()
    assert await m.refresh(allow_interactive=False) is False
    assert m.state == AuthState.EXPIRED
    assert m.credentials is None


async def test_login_now_failure_drops(monkeypatch, auth_dir) -> None:
    write_auth(auth_dir)
    monkeypatch.setattr(browser, "login_and_save_auth", login_fail())
    m = AuthManager(interactive=True, refresh_interval_h=999)
    await m.startup()
    await m.shutdown()
    assert await m.login_now() is False
    assert m.state == AuthState.EXPIRED


async def test_singleflight_dedup(monkeypatch, auth_dir) -> None:
    write_auth(auth_dir)
    calls = 0

    async def counting():
        nonlocal calls
        calls += 1
        await asyncio.sleep(0.05)
        return {"token": "t", "cookieHeader": "ds_session_id=s"}

    monkeypatch.setattr(browser, "refresh_auth_from_profile", counting)
    m = AuthManager(interactive=False, refresh_interval_h=999)
    await m.startup()
    await m.shutdown()
    results = await asyncio.gather(m.refresh(), m.refresh(), m.refresh())
    assert all(results)
    assert calls == 2  # startup + ровно один refresh на троих


async def test_call_with_refresh_retries_with_new_creds(monkeypatch, auth_dir) -> None:
    write_auth(auth_dir)
    monkeypatch.setattr(browser, "refresh_auth_from_profile", silent_ok())
    m = AuthManager(interactive=False, refresh_interval_h=999)
    await m.startup()
    await m.shutdown()

    attempts = 0

    async def factory():
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise AuthRequiredError("boom")
        return "ok:" + m.credentials["token"]

    assert await m.call_with_refresh(factory) == "ok:fresh-tok"
    assert attempts == 2


async def test_call_with_refresh_gives_up(monkeypatch, auth_dir) -> None:
    write_auth(auth_dir)
    monkeypatch.setattr(browser, "refresh_auth_from_profile", silent_fail())
    m = AuthManager(interactive=False, refresh_interval_h=999)
    await m.startup()
    await m.shutdown()

    async def factory():
        raise AuthRequiredError("boom")

    with pytest.raises(AuthRequiredError):
        await m.call_with_refresh(factory)
