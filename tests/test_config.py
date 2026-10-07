"""Тесты .env-загрузчика конфига."""
from __future__ import annotations

import os

from deepseek_free_api import config


def test_dotenv_sets_missing_vars(tmp_path, monkeypatch) -> None:
    (tmp_path / ".env").write_text(
        '# комментарий\nHOST=0.0.0.0\nQUOTED="значение"\nПУСТАЯ=\n', encoding="utf-8"
    )
    monkeypatch.chdir(tmp_path)
    monkeypatch.delenv("HOST", raising=False)
    monkeypatch.delenv("QUOTED", raising=False)
    config._load_dotenv()
    assert os.environ["HOST"] == "0.0.0.0"
    assert os.environ["QUOTED"] == "значение"


def test_dotenv_does_not_override_real_env(tmp_path, monkeypatch) -> None:
    (tmp_path / ".env").write_text("HOST=0.0.0.0\n", encoding="utf-8")
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("HOST", "127.0.0.1")
    config._load_dotenv()
    assert os.environ["HOST"] == "127.0.0.1"


def test_dotenv_missing_file_ok(tmp_path, monkeypatch) -> None:
    monkeypatch.chdir(tmp_path)
    config._load_dotenv()  # не падает
