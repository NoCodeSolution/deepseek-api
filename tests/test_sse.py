"""Тесты SSE-парсера и извлечения дельт."""
from __future__ import annotations

from deepseek_free_api.sse import (
    extract_delta_text,
    parse_sse_event,
    stream_sse,
)


class FakeResponse:
    def __init__(self, chunks: list[str]) -> None:
        self._chunks = chunks

    async def aiter_text(self):
        for chunk in self._chunks:
            yield chunk


# ─── parse_sse_event ────────────────────────────────────────


def test_parse_event_multiline_data() -> None:
    event = parse_sse_event("event: message\ndata: {\"a\": 1}\ndata: {\"b\": 2}")
    assert event["event"] == "message"
    assert event["data"] == '{"a": 1}\n{"b": 2}'


def test_parse_event_crlf() -> None:
    event = parse_sse_event("event: x\r\ndata: y")
    assert event == {"event": "x", "data": "y"}


def test_parse_event_data_lstrip() -> None:
    event = parse_sse_event("data:   spaced")
    assert event["data"] == "spaced"


# ─── extract_delta_text ─────────────────────────────────────


def test_append_format() -> None:
    cache: dict[str, str] = {}
    payload = {"p": "/messages/0/content", "o": "APPEND", "v": "привет"}
    text, reasoning, mid = extract_delta_text(payload, cache)
    assert text == "привет"
    assert reasoning == ""
    assert mid is None


def test_single_v_root() -> None:
    cache: dict[str, str] = {}
    text, reasoning, _ = extract_delta_text({"v": "кусок"}, cache)
    assert text == "кусок"
    assert reasoning == ""


def test_batch_format() -> None:
    cache: dict[str, str] = {}
    payload = {
        "o": "BATCH",
        "v": [
            {"p": "/a/content", "o": "APPEND", "v": "раз"},
            {"p": "/b/content", "o": "APPEND", "v": "два"},
        ],
    }
    text, _, _ = extract_delta_text(payload, cache)
    assert text == "раздва"


def test_response_cache_delta() -> None:
    cache: dict[str, str] = {}
    first = {"type": "RESPONSE", "content": "abcd", "response_message_id": 42}
    text1, _, mid1 = extract_delta_text(first, cache)
    assert text1 == "abcd"
    assert mid1 == 42
    text2, _, _ = extract_delta_text(dict(first), cache)
    assert text2 == ""
    text3, _, _ = extract_delta_text(dict(first, content="abcdef"), cache)
    assert text3 == "ef"


def test_think_goes_to_reasoning() -> None:
    cache: dict[str, str] = {}
    text, reasoning, mid = extract_delta_text(
        {"type": "THINK", "content": "думаю", "message_id": 7}, cache
    )
    assert text == ""
    assert reasoning == "думаю"
    assert mid == 7


def test_openai_choices_format() -> None:
    cache: dict[str, str] = {}
    text, _, _ = extract_delta_text({"choices": [{"delta": {"content": "chunk"}}]}, cache)
    assert text == "chunk"


# ─── stream_sse ─────────────────────────────────────────────


async def test_stream_sse_end_to_end() -> None:
    events = [
        'data: {"p": "/m/0/content", "o": "APPEND", "v": "При',
        'вет", "response_message_id": 5}\n\n',
        'data: {"type": "THINK", "content": "рассуждение", "message_id": 5}\n\n',
        "data: [DONE]\n\n",
    ]
    deltas: list[tuple[str, str]] = []
    mid, text, reasoning = await stream_sse(
        FakeResponse(events), on_delta=lambda t, r: deltas.append((t, r))
    )
    assert mid == 5
    assert text == "Привет"
    assert reasoning == "рассуждение"
    assert ("Привет", "") in deltas
    assert ("", "рассуждение") in deltas


async def test_stream_sse_event_split_across_chunks() -> None:
    events = ['data: {"v": "раз', 'бито"}\n\n']
    _, text, _ = await stream_sse(FakeResponse(events))
    assert text == "разбито"


async def test_stream_sse_ignores_done_marker_and_bad_json() -> None:
    events = ['data: {broken\n\n', 'data: {"v": "x"}\n\n', "data: [DONE]\n\n"]
    _, text, _ = await stream_sse(FakeResponse(events))
    assert text == "x"
