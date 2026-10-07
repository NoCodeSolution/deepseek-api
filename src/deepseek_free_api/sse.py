"""Парсер SSE и извлечение дельт текста (порт src/sse.mjs, логика 1:1)."""
from __future__ import annotations

import json
import re
from typing import Any, Callable

OnText = Callable[[str, str], None]  # (text_piece, reasoning_piece) — либо одно пустое

_LINE_SPLIT = re.compile(r"\r?\n")


async def stream_sse(
    response: Any,
    on_delta: OnText | None = None,
    debug: bool = False,
) -> tuple[int | None, str, str]:
    """Читает SSE из httpx-стрима.

    Возвращает (last_assistant_message_id, full_text, full_reasoning).
    """
    last_assistant_message_id: int | None = None
    fragments: dict[str, str] = {}
    full_text = ""
    full_reasoning = ""
    buffer = ""

    async for chunk in response.aiter_text():
        buffer += chunk
        while True:
            boundary = buffer.find("\n\n")
            if boundary < 0:
                break
            raw_event = buffer[:boundary]
            buffer = buffer[boundary + 2 :]
            event = parse_sse_event(raw_event)
            if not event["data"]:
                continue
            if debug:
                print(f"[event] {event['event'] or 'message'} {event['data'][:500]}")
            try:
                parsed = json.loads(event["data"])
            except json.JSONDecodeError:
                continue
            text, reasoning, message_id = extract_delta_text(
                parsed, fragments, event["event"]
            )
            if message_id is not None:
                last_assistant_message_id = message_id
            if text:
                full_text += text
                if on_delta is not None:
                    on_delta(text, "")
            if reasoning:
                full_reasoning += reasoning
                if on_delta is not None:
                    on_delta("", reasoning)

    return last_assistant_message_id, full_text, full_reasoning


def parse_sse_event(raw: str) -> dict[str, str]:
    event = {"event": "", "data": ""}
    for line in _LINE_SPLIT.split(raw):
        if line.startswith("event:"):
            event["event"] = line[6:].strip()
        elif line.startswith("data:"):
            event["data"] += ("\n" if event["data"] else "") + line[5:].lstrip()
    return event


def _is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def extract_delta_text(
    value: Any,
    cache: dict[str, str],
    event_name: str = "",
) -> tuple[str, str, int | None]:
    """Извлекает дельту текста и рассуждений из события DeepSeek.

    Возвращает (text, reasoning, message_id). THINK-события уходят в reasoning.
    """
    message_id: int | None = None
    parts: list[str] = []
    reasoning_parts: list[str] = []

    def visit(node: Any, path: str) -> None:
        nonlocal message_id
        if node is None or not isinstance(node, (dict, list)):
            return

        if isinstance(node, dict):
            if _is_number(node.get("response_message_id")):
                message_id = node["response_message_id"]
            if _is_number(node.get("message_id")):
                message_id = node["message_id"]
            if _is_number(node.get("id")) and node.get("role") == "ASSISTANT":
                message_id = node["id"]

            if path == "$" and len(node) == 1 and isinstance(node.get("v"), str):
                parts.append(node["v"])
                return

            if (
                node.get("o") == "APPEND"
                and isinstance(node.get("p"), str)
                and node["p"].endswith("/content")
                and isinstance(node.get("v"), str)
            ):
                parts.append(node["v"])
                return

            if node.get("o") == "BATCH" and isinstance(node.get("v"), list):
                for index, item in enumerate(node["v"]):
                    visit(item, f"{path}.v.{index}")
                return

            if isinstance(node.get("content"), str) and node.get("type") in (
                "RESPONSE",
                "TEMPLATE_RESPONSE",
                "THINK",
            ):
                key = f"{message_id if message_id is not None else 'unknown'}:{path}:{node['type']}"
                previous = cache.get(key, "")
                current = node["content"]
                delta = current[len(previous) :] if current.startswith(previous) else current
                cache[key] = current
                if node["type"] == "THINK":
                    reasoning_parts.append(delta)
                else:
                    parts.append(delta)

            choices = node.get("choices")
            if isinstance(choices, list) and choices and isinstance(choices[0], dict):
                delta = choices[0].get("delta")
                if isinstance(delta, dict) and isinstance(delta.get("content"), str):
                    parts.append(delta["content"])

            for key, item in node.items():
                if key in ("content", "choices"):
                    continue
                visit(item, f"{path}.{key}")
            return

        for index, item in enumerate(node):
            visit(item, f"{path}.{index}")

    visit(value, "$")
    return "".join(parts), "".join(reasoning_parts), message_id
