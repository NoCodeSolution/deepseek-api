"""Конвертация OpenAI-формата ↔ DeepSeek web-формат.

Порт server.mjs + улучшение: system-сообщения выносятся в префикс
(в Node-версии любая роль кроме assistant превращалась в «User:»).
"""
from __future__ import annotations

import json
import time
from typing import Any

# ─── Messages → prompt ──────────────────────────────────────


def _content_to_text(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "\n".join(
            part["text"] for part in content if isinstance(part, dict) and part.get("type") == "text"
        )
    return ""


def messages_to_prompt(messages: list[dict[str, Any]]) -> str:
    system_parts: list[str] = []
    dialogue: list[str] = []
    for message in messages:
        role = message.get("role")
        text = _content_to_text(message.get("content")).strip()
        if role == "system":
            if text:
                system_parts.append(text)
            continue
        speaker = "Assistant" if role == "assistant" else "User"
        dialogue.append(f"{speaker}: {text}")

    blocks: list[str] = []
    if system_parts:
        blocks.append("<system>\n" + "\n\n".join(system_parts) + "\n</system>")
    blocks.append("\n\n".join(dialogue) + "\n\nAssistant:")
    return "\n\n".join(blocks)


# ─── OpenAI-ответы ──────────────────────────────────────────


def completion_id() -> str:
    return f"chatcmpl-{int(time.time() * 1000)}"


def now_unix() -> int:
    return int(time.time())


def openai_chunk(
    chunk_id: str,
    created: int,
    model: str,
    content: str | None = None,
    finish_reason: str | None = None,
    reasoning_content: str | None = None,
) -> dict[str, Any]:
    delta: dict[str, Any] = {}
    if content:
        delta["content"] = content
        delta["role"] = "assistant"
    if reasoning_content:
        delta["reasoning_content"] = reasoning_content
    return {
        "id": chunk_id,
        "object": "chat.completion.chunk",
        "created": created,
        "model": model,
        "choices": [
            {
                "index": 0,
                "delta": delta,
                "logprobs": None,
                "finish_reason": finish_reason,
            }
        ],
    }


def openai_full(
    full_id: str,
    created: int,
    model: str,
    content: str,
    usage: dict[str, int] | None = None,
    reasoning_content: str | None = None,
) -> dict[str, Any]:
    message: dict[str, Any] = {"role": "assistant", "content": content}
    if reasoning_content:
        message["reasoning_content"] = reasoning_content
    return {
        "id": full_id,
        "object": "chat.completion",
        "created": created,
        "model": model,
        "choices": [
            {
                "index": 0,
                "message": message,
                "logprobs": None,
                "finish_reason": "stop",
            }
        ],
        "usage": usage
        or {
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
        },
    }


def sse_frame(payload: dict[str, Any] | str) -> str:
    body = payload if isinstance(payload, str) else json.dumps(payload, ensure_ascii=False)
    return f"data: {body}\n\n"


def openai_done_frame() -> str:
    return "data: [DONE]\n\n"


# ─── Ошибки ─────────────────────────────────────────────────


def error_body(error_type: str, message: str) -> dict[str, Any]:
    return {"error": {"type": error_type, "message": message}}


def estimate_tokens(text: str) -> int:
    return max(1, len(text) // 4) if text else 0
