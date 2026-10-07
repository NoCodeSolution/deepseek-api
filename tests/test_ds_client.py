"""Тесты DeepSeekClient на respx-моках."""
from __future__ import annotations

import base64
import json

import pytest
import respx

from deepseek_free_api import ds_client
from deepseek_free_api.ds_client import (
    AuthRequiredError,
    DeepSeekClient,
    RateLimitError,
    UpstreamError,
)

SESSION_JSON = {"code": 0, "data": {"biz_data": {"chat_session": {"id": "sess-1"}}}}

CHALLENGE_JSON = {
    "code": 0,
    "data": {
        "biz_data": {
            "challenge": {
                "algorithm": "DeepSeekHashV1",
                "challenge": "aabbcc",
                "salt": "42",
                "difficulty": 1,
                "expire_at": 1893456000,
                "signature": "sig",
            }
        }
    },
}

SSE_BODY = (
    'data: {"v": "При"}\n\n'
    'data: {"v": "вет"}\n\n'
    'data: {"type": "THINK", "content": "думаю", "message_id": 9}\n\n'
)


@pytest.fixture()
async def client():
    c = DeepSeekClient("ds_session_id=s", "tok")
    yield c
    await c.aclose()


# ─── create_session ─────────────────────────────────────────


@respx.mock
async def test_create_session_ok(client: DeepSeekClient) -> None:
    respx.post("/api/v0/chat_session/create").respond(json=SESSION_JSON)
    assert await client.create_session() == "sess-1"


@respx.mock
async def test_create_session_http_401(client: DeepSeekClient) -> None:
    respx.post("/api/v0/chat_session/create").respond(401, json={"code": 40001})
    with pytest.raises(AuthRequiredError):
        await client.create_session()


@respx.mock
async def test_create_session_code_40002(client: DeepSeekClient) -> None:
    respx.post("/api/v0/chat_session/create").respond(200, json={"code": 40002})
    with pytest.raises(AuthRequiredError):
        await client.create_session()


@respx.mock
async def test_create_session_429(client: DeepSeekClient) -> None:
    respx.post("/api/v0/chat_session/create").respond(429, json={"code": 1})
    with pytest.raises(RateLimitError):
        await client.create_session()


@respx.mock
async def test_create_session_500(client: DeepSeekClient) -> None:
    respx.post("/api/v0/chat_session/create").respond(500, text="boom")
    with pytest.raises(UpstreamError):
        await client.create_session()


@respx.mock
async def test_create_session_business_error(client: DeepSeekClient) -> None:
    respx.post("/api/v0/chat_session/create").respond(
        200, json={"code": 50001, "msg": "server busy"}
    )
    with pytest.raises(UpstreamError, match="server busy"):
        await client.create_session()


# ─── PoW-заголовок ──────────────────────────────────────────


@respx.mock
async def test_pow_header_payload(client: DeepSeekClient, monkeypatch) -> None:
    monkeypatch.setattr(ds_client, "solve_pow", lambda challenge: 12345)
    respx.post("/api/v0/chat/create_pow_challenge").respond(json=CHALLENGE_JSON)
    respx.post("/api/v0/chat/completion").respond(
        200,
        headers={"content-type": "text/event-stream"},
        content='data: {"v": "x"}\n\n',
    )
    await client.complete(session_id="s", prompt="hi")
    request = respx.calls.last.request
    header = request.headers["X-DS-PoW-Response"]
    payload = json.loads(base64.b64decode(header).decode())
    assert payload == {
        "algorithm": "DeepSeekHashV1",
        "challenge": "aabbcc",
        "salt": "42",
        "answer": 12345,
        "signature": "sig",
        "target_path": "/api/v0/chat/completion",
    }


# ─── complete ───────────────────────────────────────────────


@respx.mock
async def test_complete_collects_text_and_reasoning(
    client: DeepSeekClient, monkeypatch
) -> None:
    monkeypatch.setattr(ds_client, "solve_pow", lambda challenge: 1)
    respx.post("/api/v0/chat/create_pow_challenge").respond(json=CHALLENGE_JSON)
    route = respx.post("/api/v0/chat/completion").respond(
        200,
        headers={"content-type": "text/event-stream"},
        content=SSE_BODY,
    )
    deltas: list[tuple[str, str]] = []
    mid, text, reasoning = await client.complete(
        session_id="s",
        prompt="hi",
        thinking_enabled=True,
        on_delta=lambda t, r: deltas.append((t, r)),
    )
    assert mid == 9
    assert text == "Привет"
    assert reasoning == "думаю"
    body = json.loads(route.calls.last.request.content)
    assert body["thinking_enabled"] is True
    assert body["chat_session_id"] == "s"


@respx.mock
async def test_complete_non_sse_error_mapped(client: DeepSeekClient, monkeypatch) -> None:
    monkeypatch.setattr(ds_client, "solve_pow", lambda challenge: 1)
    respx.post("/api/v0/chat/create_pow_challenge").respond(json=CHALLENGE_JSON)
    respx.post("/api/v0/chat/completion").respond(403, text="forbidden")
    with pytest.raises(AuthRequiredError):
        await client.complete(session_id="s", prompt="hi")


@respx.mock
async def test_complete_429_mapped(client: DeepSeekClient, monkeypatch) -> None:
    monkeypatch.setattr(ds_client, "solve_pow", lambda challenge: 1)
    respx.post("/api/v0/chat/create_pow_challenge").respond(json=CHALLENGE_JSON)
    respx.post("/api/v0/chat/completion").respond(429, text="slow down")
    with pytest.raises(RateLimitError):
        await client.complete(session_id="s", prompt="hi")


# ─── delete_session ─────────────────────────────────────────


@respx.mock
async def test_delete_session_ok_and_failure_is_swallowed(
    client: DeepSeekClient,
) -> None:
    ok = respx.post("/api/v0/chat_session/delete").respond(json={"code": 0})
    assert await client.delete_session("sess-x") is True
    assert ok.calls.last.request.content and b"sess-x" in ok.calls.last.request.content

    ok.respond(500, json={"code": 1, "msg": "nope"})
    assert await client.delete_session("sess-x") is False  # не бросает
