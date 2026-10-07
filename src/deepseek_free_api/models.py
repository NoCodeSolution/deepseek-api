"""Pydantic-схемы OpenAI-совместимых запросов."""
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from .config import DEFAULT_MODEL


class ChatMessage(BaseModel):
    model_config = ConfigDict(extra="allow")

    role: str
    content: Any = ""


class ChatCompletionRequest(BaseModel):
    model_config = ConfigDict(extra="allow")

    model: str = DEFAULT_MODEL
    messages: list[ChatMessage] = Field(default_factory=list)
    stream: bool = False
    temperature: float | None = None
    max_tokens: int | None = None
