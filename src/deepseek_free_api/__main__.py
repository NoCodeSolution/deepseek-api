"""CLI-вход: serve (по умолчанию), --login, --connect, --import, --manual.

Порт флагов server.mjs:
  deepseek-free-api [порт]
  deepseek-free-api --login
  deepseek-free-api --connect [порт]
  deepseek-free-api --import <cookies.json> "<userToken>"
  deepseek-free-api --manual
"""
from __future__ import annotations

import argparse
import sys

from .auth.storage import (
    AuthStorageError,
    import_cookies,
    print_manual_instructions,
)
from .config import DEFAULT_PORT


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="deepseek-free-api",
        description="Бесплатный OpenAI-совместимый API через DeepSeek (браузерная авторизация).",
    )
    parser.add_argument("port", nargs="?", type=int, default=None, help="порт сервера")
    parser.add_argument(
        "--login",
        action="store_true",
        help="логин: headless по DS_EMAIL/DS_PASSWORD, при неудаче — окно Playwright",
    )
    parser.add_argument(
        "--connect",
        nargs="?",
        const=9222,
        default=None,
        type=int,
        metavar="PORT",
        help="забрать сессию из Chrome с remote-debugging (по умолчанию порт 9222)",
    )
    parser.add_argument(
        "--import",
        dest="import_",
        nargs=2,
        metavar=("COOKIES_JSON", "USERTOKEN"),
        help="импорт cookies из файла + userToken вручную",
    )
    parser.add_argument("--manual", action="store_true", help="инструкция по ручному экспорту")
    return parser


def main(argv: list[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, OSError):
            pass

    args = build_parser().parse_args(argv)

    if args.manual:
        print_manual_instructions()
        return 0

    if args.import_:
        cookies_file, user_token = args.import_
        try:
            import_cookies(cookies_file, user_token)
        except AuthStorageError as exc:
            print(f"❌ {exc}", file=sys.stderr)
            return 1
        print("✅ Cookies и токен импортированы!\n")
        print("Запускай: deepseek-free-api")
        return 0

    if args.login:
        import asyncio

        from . import config
        from .auth import browser
        from .auth.browser import headless_credentials_login, login_and_save_auth

        async def _login() -> None:
            if config.DS_EMAIL and config.DS_PASSWORD:
                try:
                    await headless_credentials_login()
                    return
                except browser.BrowserAuthError as exc:
                    print(f"⚠️ Headless-логин не удался ({exc}), открываю окно...")
                except Exception as exc:  # noqa: BLE001 - не светим креды в stderr
                    print(
                        f"⚠️ Headless-логин упал ({type(exc).__name__}), открываю окно..."
                    )
            await login_and_save_auth()

        try:
            asyncio.run(_login())
        except Exception as exc:  # noqa: BLE001 - CLI: любая ошибка -> exit 1
            print(f"❌ {exc}", file=sys.stderr)
            return 1
        print("✅ Авторизация DeepSeek сохранена!\n")
        return 0

    if args.connect is not None:
        import asyncio

        from .auth.browser import connect_to_running_chrome

        try:
            asyncio.run(connect_to_running_chrome(args.connect))
        except Exception as exc:  # noqa: BLE001
            print(f"❌ {exc}", file=sys.stderr)
            return 1
        print("✅ Подключение готово. Запускай: deepseek-free-api")
        return 0

    from .server import serve

    serve(port=args.port)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
