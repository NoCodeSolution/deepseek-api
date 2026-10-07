"""Тесты конвертации OpenAI ↔ prompt/chunk-форматы."""
from __future__ import annotations

from deepseek_free_api.converter import (
    error_body,
    estimate_tokens,
    messages_to_prompt,
    openai_chunk,
    openai_done_frame,
    openai_full,
    sse_frame,
)


def test_system_goes_to_prefix() -> None:
    prompt = messages_to_prompt(
        [
            {"role": "system", "content": "Будь вежлив."},
            {"role": "user", "content": "Привет"},
            {"role": "assistant", "content": "Здравствуй!"},
            {"role": "user", "content": "Как дела?"},
        ]
    )
    assert prompt.startswith("<system>\nБудь вежлив.\n</system>")
    assert "User: Привет" in prompt
    assert "Assistant: Здравствуй!" in prompt
    assert prompt.endswith("\n\nAssistant:")


def test_no_system_messages() -> None:
    prompt = messages_to_prompt([{"role": "user", "content": "hi"}])
    assert "<system>" not in prompt
    assert prompt == "User: hi\n\nAssistant:"


def test_content_array_takes_text_parts() -> None:
    prompt = messages_to_prompt(
        [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "часть1"},
                    {"type": "image_url", "url": "..."},
                    {"type": "text", "text": "часть2"},
                ],
            }
        ]
    )
    assert "часть1\nчасть2" in prompt


def test_empty_messages() -> None:
    prompt = messages_to_prompt([])
    assert prompt.endswith("\n\nAssistant:")


def test_openai_chunk_content_and_reasoning() -> None:
    chunk = openai_chunk("id1", 100, "deepseek-reasoner", content="abc")
    assert chunk["choices"][0]["delta"]["content"] == "abc"
    assert chunk["choices"][0]["delta"]["role"] == "assistant"

    rchunk = openai_chunk(
        "id1", 100, "deepseek-reasoner", reasoning_content="думаю"
    )
    assert rchunk["choices"][0]["delta"]["reasoning_content"] == "думаю"
    assert "content" not in rchunk["choices"][0]["delta"]

    fin = openai_chunk("id1", 100, "m", finish_reason="stop")
    assert fin["choices"][0]["finish_reason"] == "stop"


def test_openai_full_usage_and_reasoning() -> None:
    full = openai_full(
        "id1",
        100,
        "deepseek-reasoner",
        "ответ",
        usage={"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
        reasoning_content="рассуждение",
    )
    assert full["choices"][0]["message"]["content"] == "ответ"
    assert full["choices"][0]["message"]["reasoning_content"] == "рассуждение"
    assert full["usage"]["total_tokens"] == 15


def test_sse_frame_and_done() -> None:
    assert sse_frame({"a": 1}).startswith("data: {")
    assert sse_frame({"a": 1}).endswith("\n\n")
    assert openai_done_frame() == "data: [DONE]\n\n"


def test_error_body_shape() -> None:
    body = error_body("authentication_error", "msg")
    assert body == {"error": {"type": "authentication_error", "message": "msg"}}


def test_estimate_tokens() -> None:
    assert estimate_tokens("") == 0
    assert estimate_tokens("abcd") == 1
    assert estimate_tokens("x" * 400) == 100
