"""Общие фикстуры и опции pytest."""
from __future__ import annotations

import pytest


def pytest_addoption(parser: pytest.Parser) -> None:
    parser.addoption(
        "--live",
        action="store_true",
        default=False,
        help="запускать live-тесты (нужна живая сессия DeepSeek в ~/.deepseek-free-api)",
    )


def pytest_collection_modifyitems(config: pytest.Config, items: list[pytest.Item]) -> None:
    if config.getoption("--live"):
        return
    skip_live = pytest.mark.skip(reason="live-тест: запускать с --live")
    for item in items:
        if "live" in item.keywords:
            item.add_marker(skip_live)
