"""Тесты HTTP-слоя (FastAPI TestClient + подмена DeepSeekClient/AuthManager)."""
from __future__ import annotations

from typing import Any

import pytest
from fastapi.testclient import TestClient

from deepseek_free_api import server
from deepseek_free_api.auth.manager import AuthState
from deepseek_free_api.ds_client import AuthRequiredError


class StubManager:
    def __init__(self, creds: dict | None = None) -> None:
        self._creds = creds or {"cookieHeader": "ds_session_id=s", "token": "t"}
        self.state = AuthState.READY

    @property
    def credentials(self):
        return self._creds

    @property
    def status_dict(self) -> dict[str, Any]:
        return {"state": self.state.value, "has_token": bool(self._creds)}

    async def startup(self) -> None: ...

    async def shutdown(self) -> None: ...

    async def refresh(self, *, allow_interactive: bool = True) -> bool:
        return True

    async def login_now(self) -> bool:
        return True


class FakeClient:
    """Имитация DeepSeekClient: отдаёт текст/рассуждение, записывает kwargs."""

    def __init__(self, *, fail_with: Exception | None = None) -> None:
        self.fail_with = fail_with
        self.calls: list[dict[str, Any]] = []
        self.deleted: list[str] = []

    async def create_session(self) -> str:
        if self.fail_with:
            raise self.fail_with
        return "fake-session"

    async def complete(self, **kwargs):
        self.calls.append(kwargs)
        if self.fail_with:
            raise self.fail_with
        on_delta = kwargs.get("on_delta")
        if on_delta:
            on_delta("Ответ", "")
            on_delta("", "Рассуждение")
        return 1, "Ответ", "Рассуждение"

    async def delete_session(self, session_id: str) -> bool:
        self.deleted.append(session_id)
        return True

    async def aclose(self) -> None: ...


@pytest.fixture()
def stub_manager(monkeypatch) -> StubManager:
    stub = StubManager()
    monkeypatch.setattr(server, "auth_manager", stub)
    return stub


@pytest.fixture()
def fake_client(monkeypatch) -> FakeClient:
    fake = FakeClient()
    monkeypatch.setattr(server, "_new_client", lambda: fake)
    return fake


@pytest.fixture()
def api(stub_manager) -> TestClient:
    with TestClient(server.app) as client:
        yield client


# ─── базовые роуты ──────────────────────────────────────────


def test_models(api: TestClient) -> None:
    response = api.get("/v1/models")
    assert response.status_code == 200
    ids = [m["id"] for m in response.json()["data"]]
    assert ids == ["deepseek-chat", "deepseek-reasoner", "deepseek-r1"]


def test_health(api: TestClient) -> None:
    response = api.get("/health")
    assert response.status_code == 200
    assert response.json()["state"] == "ready"


def test_auth_status(api: TestClient) -> None:
    assert api.get("/v1/auth/status").status_code == 200


# ─── chat completions ───────────────────────────────────────


def test_non_stream_completion(api: TestClient, fake_client: FakeClient, monkeypatch) -> None:
    monkeypatch.setattr(server, "RESPONSE_FORMAT", "openai")
    response = api.post(
        "/v1/chat/completions",
        json={
            "model": "deepseek-reasoner",
            "messages": [
                {"role": "system", "content": "sys"},
                {"role": "user", "content": "вопрос"},
            ],
        },
    )
    assert response.status_code == 200
    body = response.json()
    message = body["choices"][0]["message"]
    assert message["content"] == "Ответ"
    assert message["reasoning_content"] == "Рассуждение"
    assert body["usage"]["total_tokens"] > 0

    call = fake_client.calls[0]
    assert call["thinking_enabled"] is True  # reasoner → thinking on
    assert "<system>" in call["prompt"]
    # чат удалён после ответа
    assert fake_client.deleted == ["fake-session"]


def test_non_stream_chat_model_no_thinking(
    api: TestClient, fake_client: FakeClient
) -> None:
    response = api.post(
        "/v1/chat/completions",
        json={"model": "deepseek-chat", "messages": [{"role": "user", "content": "q"}]},
    )
    assert response.status_code == 200
    assert fake_client.calls[0]["thinking_enabled"] is False


def test_non_stream_plain_text_default(
    api: TestClient, fake_client: FakeClient, monkeypatch
) -> None:
    """Дефолтный формат — голый текст для TMS, без JSON-обёртки."""
    monkeypatch.setattr(server, "RESPONSE_FORMAT", "text")
    response = api.post(
        "/v1/chat/completions",
        json={"model": "deepseek-chat", "messages": [{"role": "user", "content": "q"}]},
    )
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/plain")
    assert response.text == "Ответ"


def test_default_model_when_absent(api: TestClient, fake_client: FakeClient, monkeypatch) -> None:
    monkeypatch.setattr(server, "RESPONSE_FORMAT", "openai")
    monkeypatch.setattr(server, "DEFAULT_MODEL", "deepseek-chat")
    response = api.post(
        "/v1/chat/completions",
        json={"messages": [{"role": "user", "content": "q"}]},
    )
    assert response.status_code == 200
    assert response.json()["model"] == "deepseek-chat"
    assert fake_client.calls[0]["thinking_enabled"] is False


def test_unknown_model_maps_to_default(
    api: TestClient, fake_client: FakeClient, monkeypatch
) -> None:
    monkeypatch.setattr(server, "RESPONSE_FORMAT", "openai")
    monkeypatch.setattr(server, "DEFAULT_MODEL", "deepseek-chat")
    response = api.post(
        "/v1/chat/completions",
        json={"model": "gpt-4o", "messages": [{"role": "user", "content": "q"}]},
    )
    assert response.status_code == 200
    assert response.json()["model"] == "deepseek-chat"
    assert fake_client.calls[0]["model_type"] is None


def test_auth_error_maps_401(api: TestClient, monkeypatch) -> None:
    fake = FakeClient(fail_with=AuthRequiredError("code 40002"))
    monkeypatch.setattr(server, "_new_client", lambda: fake)
    response = api.post(
        "/v1/chat/completions",
        json={"model": "deepseek-chat", "messages": [{"role": "user", "content": "q"}]},
    )
    assert response.status_code == 401
    assert response.json()["error"]["type"] == "authentication_error"


def test_stream_completion(api: TestClient, fake_client: FakeClient) -> None:
    response = api.post(
        "/v1/chat/completions",
        json={
            "model": "deepseek-reasoner",
            "stream": True,
            "messages": [{"role": "user", "content": "q"}],
        },
    )
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    body = response.text
    assert '"reasoning_content": "Рассуждение"' in body
    assert '"content": "Ответ"' in body
    assert '"finish_reason": "stop"' in body
    assert "data: [DONE]" in body
    assert fake_client.deleted == ["fake-session"]


# ─── PROXY_API_KEY ──────────────────────────────────────────


def test_proxy_api_key_enforced(api: TestClient, fake_client: FakeClient, monkeypatch) -> None:
    monkeypatch.setattr(server, "PROXY_API_KEY", "sekret")
    response = api.post(
        "/v1/chat/completions",
        json={"model": "deepseek-chat", "messages": [{"role": "user", "content": "q"}]},
    )
    assert response.status_code == 401
    assert response.json()["error"]["message"] == "Invalid or missing API key"

    response = api.post(
        "/v1/chat/completions",
        headers={"Authorization": "Bearer sekret"},
        json={"model": "deepseek-chat", "messages": [{"role": "user", "content": "q"}]},
    )
    assert response.status_code == 200
