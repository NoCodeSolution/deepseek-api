"""FastAPI-сервер: OpenAI-совместимый прокси поверх DeepSeek web.

Режим чатов: 1 вопрос = 1 чат — каждый запрос создаёт сессию DeepSeek,
после ответа (успех/ошибка/обрыв) сессия удаляется.
Авторизация: AuthManager (тихий refresh из профиля + интерактивная эскалация).
"""
from __future__ import annotations

import asyncio
import logging
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, PlainTextResponse, StreamingResponse

from .auth.manager import AuthManager
from .config import (
    BASE_URL,
    DEFAULT_HOST,
    DEFAULT_MODEL,
    DEFAULT_PORT,
    LOG_REQUEST_BODIES,
    RESPONSE_FORMAT,
)
from .converter import (
    completion_id,
    error_body,
    estimate_tokens,
    messages_to_prompt,
    now_unix,
    openai_chunk,
    openai_done_frame,
    openai_full,
    sse_frame,
)
from .ds_client import (
    AuthRequiredError,
    DeepSeekClient,
    DeepSeekError,
    RateLimitError,
    UpstreamError,
)
from .models import ChatCompletionRequest

logger = logging.getLogger(__name__)

DELETE_CHAT = os.environ.get("DELETE_CHAT", "1") != "0"
DEBUG = os.environ.get("DEBUG", "") == "1"
PROXY_API_KEY = os.environ.get("PROXY_API_KEY", "")
CORS_ORIGIN = os.environ.get("CORS_ORIGIN", "")
RETRY_BACKOFF_S = float(os.environ.get("RETRY_BACKOFF_S", "2"))

auth_manager = AuthManager()


def _new_client() -> DeepSeekClient:
    creds = auth_manager.credentials or {"cookieHeader": "", "token": ""}
    return DeepSeekClient(creds["cookieHeader"], creds["token"], debug=DEBUG)


def _error_status(exc: Exception) -> int:
    if isinstance(exc, AuthRequiredError):
        return 401
    if isinstance(exc, RateLimitError):
        return 429
    if isinstance(exc, (UpstreamError, DeepSeekError)):
        return 502
    return 500


def _error_payload(exc: Exception) -> dict:
    if isinstance(exc, AuthRequiredError):
        return error_body(
            "authentication_error",
            f"Сессия DeepSeek истекла: {exc}. Выполни: deepseek-free-api --login",
        )
    if isinstance(exc, RateLimitError):
        return error_body("rate_limit_error", str(exc))
    if isinstance(exc, (UpstreamError, DeepSeekError)):
        return error_body("upstream_error", str(exc))
    logger.exception("Внутренняя ошибка")
    return error_body("internal_error", str(exc))


async def _maybe_delete(client: DeepSeekClient, session_id: str | None) -> None:
    if not DELETE_CHAT or not session_id:
        return
    try:
        await asyncio.shield(client.delete_session(session_id))
    except asyncio.CancelledError:
        logger.debug("Удаление чата %s прервано", session_id)
    except Exception as exc:  # noqa: BLE001 - best-effort
        logger.warning("Удаление чата %s не удалось: %s", session_id, exc)


def _resolve_model_type(model: str) -> str | None:
    lowered = model.lower()
    if "reasoner" in lowered or "r1" in lowered:
        return "deepseek-reasoner"
    return None


def _resolve_model(model: str) -> str:
    """Нормализация модели запроса: неизвестная → DEFAULT_MODEL (без thinking)."""
    requested = (model or "").strip()
    if not requested:
        return DEFAULT_MODEL
    lowered = requested.lower()
    if lowered == DEFAULT_MODEL.lower() or "deepseek" in lowered:
        return requested
    logger.warning(
        "Неизвестная модель %r, заменяю на дефолтную %r", requested, DEFAULT_MODEL
    )
    return DEFAULT_MODEL


async def _attempt_completion(
    req: ChatCompletionRequest, queue: asyncio.Queue, emitted: list[bool]
) -> None:
    """Одна попытка: создать чат → completion → удалить чат."""
    client = _new_client()
    session_id: str | None = None
    try:
        session_id = await client.create_session()
        prompt = messages_to_prompt([m.model_dump() for m in req.messages])
        model = _resolve_model(req.model)
        model_type = _resolve_model_type(model)
        _, text, reasoning = await client.complete(
            session_id=session_id,
            prompt=prompt,
            model_type=model_type,
            thinking_enabled=model_type == "deepseek-reasoner",
            search_enabled=False,
            on_delta=lambda piece, rpiece: _emit_delta(queue, emitted, piece, rpiece),
        )
        queue.put_nowait(("done", {"text": text, "reasoning": reasoning, "prompt": prompt}))
    finally:
        await _maybe_delete(client, session_id)
        await client.aclose()


def _emit_delta(
    queue: asyncio.Queue, emitted: list[bool], piece: str, rpiece: str
) -> None:
    emitted[0] = True
    queue.put_nowait(("text", {"content": piece, "reasoning": rpiece}))


async def _run_completion(req: ChatCompletionRequest, queue: asyncio.Queue) -> None:
    emitted = [False]
    try:
        try:
            await _attempt_completion(req, queue, emitted)
        except AuthRequiredError:
            if emitted[0]:
                raise
            logger.warning("Auth error до первого текста: refresh + повтор")
            await auth_manager.refresh()
            await _attempt_completion(req, queue, emitted)
        except (RateLimitError, UpstreamError) as exc:
            if emitted[0]:
                raise
            logger.warning("Upstream ошибка (%s), повтор через %.1fs", exc, RETRY_BACKOFF_S)
            await asyncio.sleep(RETRY_BACKOFF_S)
            await _attempt_completion(req, queue, emitted)
    except Exception as exc:  # noqa: BLE001 - передаём в генератор/обработчик
        queue.put_nowait(("error", exc))


async def _stream_events(req: ChatCompletionRequest) -> AsyncIterator[str]:
    queue: asyncio.Queue = asyncio.Queue()
    runner = asyncio.create_task(_run_completion(req, queue))
    chunk_id = completion_id()
    created = now_unix()
    model = _resolve_model(req.model)
    yield sse_frame(openai_chunk(chunk_id, created, model))
    try:
        while True:
            kind, payload = await queue.get()
            if kind == "text":
                yield sse_frame(
                    openai_chunk(
                        chunk_id,
                        created,
                        model,
                        content=payload["content"] or None,
                        reasoning_content=payload["reasoning"] or None,
                    )
                )
            elif kind == "done":
                yield sse_frame(
                    openai_chunk(chunk_id, created, model, finish_reason="stop")
                )
                yield openai_done_frame()
                break
            else:  # error
                yield sse_frame(_error_payload(payload))
                break
    finally:
        if not runner.done():
            runner.cancel()
            await asyncio.gather(runner, return_exceptions=True)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    await auth_manager.startup()
    yield
    await auth_manager.shutdown()


app = FastAPI(title="DeepSeek Free API", lifespan=lifespan)


@app.middleware("http")
async def log_422_body(request: Request, call_next):
    if not LOG_REQUEST_BODIES:
        return await call_next(request)
    body = await request.body()

    async def receive():
        return {"type": "http.request", "body": body}

    request = Request(request.scope, receive)
    response = await call_next(request)
    if response.status_code == 422:
        logger.warning(
            "422 body from %s %s: %s",
            request.client.host if request.client else "?",
            request.url.path,
            body.decode(errors="replace"),
        )
    return response


@app.middleware("http")
async def proxy_api_key_guard(request: Request, call_next):
    if PROXY_API_KEY and request.method != "OPTIONS":
        auth = request.headers.get("authorization", "")
        if auth != f"Bearer {PROXY_API_KEY}":
            return JSONResponse(
                error_body("authentication_error", "Invalid or missing API key"),
                status_code=401,
            )
    return await call_next(request)


if CORS_ORIGIN:
    from fastapi.middleware.cors import CORSMiddleware

    app.add_middleware(
        CORSMiddleware,
        allow_origins=[CORS_ORIGIN],
        allow_methods=["*"],
        allow_headers=["*"],
    )


@app.get("/v1/models")
async def list_models() -> JSONResponse:
    models = [
        {"id": "deepseek-chat", "object": "model", "created": now_unix(), "owned_by": "deepseek"},
        {"id": "deepseek-reasoner", "object": "model", "created": now_unix(), "owned_by": "deepseek"},
        {"id": "deepseek-r1", "object": "model", "created": now_unix(), "owned_by": "deepseek"},
    ]
    if DEFAULT_MODEL.lower() not in {m["id"].lower() for m in models}:
        models.append(
            {"id": DEFAULT_MODEL, "object": "model", "created": now_unix(), "owned_by": "deepseek"}
        )
    return JSONResponse({"object": "list", "data": models})


@app.get("/health")
async def health() -> JSONResponse:
    return JSONResponse(
        {
            "status": "ok",
            "deepseek_url": BASE_URL,
            **auth_manager.status_dict,
        }
    )


@app.get("/v1/auth/status")
async def auth_status() -> JSONResponse:
    return JSONResponse(auth_manager.status_dict)


@app.post("/v1/auth/refresh")
async def auth_refresh() -> JSONResponse:
    ok = await auth_manager.refresh(allow_interactive=False)
    return JSONResponse(auth_manager.status_dict, status_code=200 if ok else 401)


@app.post("/v1/auth/login")
async def auth_login() -> JSONResponse:
    ok = await auth_manager.login_now()
    return JSONResponse(auth_manager.status_dict, status_code=200 if ok else 401)


@app.post("/v1/chat/completions")
async def chat_completions(req: ChatCompletionRequest, request: Request):
    if req.stream:
        return StreamingResponse(
            _stream_events(req),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "Connection": "keep-alive"},
        )

    queue: asyncio.Queue = asyncio.Queue()
    runner = asyncio.create_task(_run_completion(req, queue))
    try:
        while True:
            kind, payload = await queue.get()
            if kind in ("text",):
                continue
            if kind == "done":
                if RESPONSE_FORMAT == "text":
                    return PlainTextResponse(payload["text"])
                full_id = completion_id()
                usage = {
                    "prompt_tokens": estimate_tokens(payload["prompt"]),
                    "completion_tokens": estimate_tokens(
                        payload["text"] + payload["reasoning"]
                    ),
                }
                usage["total_tokens"] = usage["prompt_tokens"] + usage["completion_tokens"]
                return JSONResponse(
                    openai_full(
                        full_id,
                        now_unix(),
                        _resolve_model(req.model),
                        payload["text"],
                        usage=usage,
                        reasoning_content=payload["reasoning"] or None,
                    )
                )
            return JSONResponse(
                _error_payload(payload), status_code=_error_status(payload)
            )
    finally:
        if not runner.done():
            runner.cancel()
            await asyncio.gather(runner, return_exceptions=True)


def serve(port: int | None = None) -> None:
    import logging
    import uvicorn

    logging.basicConfig(level=logging.INFO)

    port = port or int(os.environ.get("PORT", DEFAULT_PORT))
    host = os.environ.get("HOST", DEFAULT_HOST)
    if host not in ("127.0.0.1", "localhost", "::1") and not PROXY_API_KEY:
        logger.warning(
            "Сервер слушает %s без PROXY_API_KEY — прокси открыт для сети! "
            "Задай PROXY_API_KEY или HOST=127.0.0.1",
            host,
        )
    display_host = host
    hint = ""
    if host in ("0.0.0.0", "::"):
        hint = "  # слушаю все интерфейсы: с других машин — по IP этого ПК"
    print(
        f"""
╔══════════════════════════════════════════════════╗
║     DeepSeek Free → OpenAI Proxy (Python)        ║
║══════════════════════════════════════════════════║
║  Порт:    {str(port):<39}║
║  Хост:    {host:<39}║
║══════════════════════════════════════════════════║
║  POST http://{display_host}:{port}/v1/chat/completions
║  GET  http://{display_host}:{port}/v1/models
║  GET  http://{display_host}:{port}/health
║  POST http://{display_host}:{port}/v1/auth/refresh
╚══════════════════════════════════════════════════╝{hint}
""",
        flush=True,
    )
    uvicorn.run(app, host=host, port=port, log_level="info")
