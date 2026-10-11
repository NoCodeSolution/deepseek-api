# DeepSeek Free API — Spec (Python-версия)

Проект: OpenAI-совместимый прокси поверх бесплатного web-акаунта DeepSeek.
Переписывание с Node.js на Python выполнено по утверждённому плану
(оригинал: `~/.kimi-code/sessions/wd_deepseek-api_c1978c29ccf2/session_c003f206-74c8-4e33-bedf-20abf643bd2d/agents/main/plans/sunspot-squirrel-girl-superman.md`).
Документ — срез состояния для продолжения в новой сессии.

## 1. Назначение и режим работы

- Принимает OpenAI-запросы (`POST /v1/chat/completions`, stream/non-stream) и проксирует в web API DeepSeek (`chat.deepseek.com/api/v0/...`) с браузерной авторизацией (cookies + userToken, без API-ключей DeepSeek).
- **Режим чатов — по решению пользователя: «1 вопрос = 1 чат, чат удаляется после ответа».** Каждый запрос: `chat_session/create` → `chat/completion` (SSE) → удаление сессии в `finally` (успех/ошибка/обрыв стрима). Флаг `DELETE_CHAT=1`.
- **Авторизация с авто-обновлением** (главная причина переписывания): `AuthManager` — silent refresh из browser-профиля → эскалация до интерактивного окна логина → деградация с осмысленным 401.

## 2. Стек и окружение

- Python 3.13 (venv в проекте: `.venv/`), пакет в `src/deepseek_free_api/`, установка `pip install -e ".[dev]"`.
- FastAPI + uvicorn, httpx (async), playwright (Python), wasmtime (PoW), pydantic v2; тесты: pytest + pytest-asyncio + respx.
- Запуск: `deepseek-free-api` или `.venv/Scripts/python.exe -m deepseek_free_api [--login|--connect [порт]|--import cookies.json "токен"|--manual] [порт]`.
- Windows: в `__main__.main()` stdout/stderr форсируются в UTF-8 (баннер падал с UnicodeEncodeError на cp1251).
- **Node-файлы удалены** (server.mjs, src/*.mjs, package.json) — проект полностью на Python. История в git.

## 3. Структура

```
pyproject.toml                     # + console-script deepseek-free-api
Dockerfile, .dockerignore          # контейнерный запуск (headless, сессия через volume)
README.md                          # Python-версия: установка, env, endpoints, Docker
src/deepseek_free_api/
  __main__.py                      # CLI: serve(по умолч.), --login/--connect/--import/--manual
  config.py                        # BASE_URL, AUTH_DIR (~/.deepseek-free-api), порт 18632, хост 127.0.0.1
  models.py                        # pydantic ChatCompletionRequest/ChatMessage
  converter.py                     # messages→prompt (system → <system>-блок), OpenAI-chunk builders, error_body
  pow_solver.py                    # PoW через wasmtime (порт src/pow.mjs)
  sse.py                           # parse_sse_event + extract_delta_text (текст И reasoning отдельно)
  ds_client.py                     # DeepSeekClient: create_session/complete/delete_session, ремап ошибок
  server.py                        # FastAPI app, AuthManager, роуты, middleware PROXY_API_KEY, retry/backoff
  auth/
    storage.py                     # auth.json v1 (совместим со старой Node-версией!), import_cookies
    browser.py                     # playwright: login/refresh/CDP, SingletonLock-cleanup
    manager.py                     # AuthManager: state machine, single-flight (generation), фоновый таймер
tests/                             # 69 тестов: conftest(--live), converter, sse, pow, storage, auth_manager, ds_client(respx), server(TestClient)
docs/SPEC.md                       # этот файл
```

## 4. Сделано (проверено)

- **Фаза 0 — PoW-spike.** WASM без импортов; glue на wasmtime (`wasm_solve(retptr,p0,l0,p1,l1,difficulty:f64)`).
  Порт ведёт себя идентично Node-референсу на всех пробах. Положительный live-путь — в фазе 7.
- **Фаза 1 — скелет.** pyproject, config, storage, converter, CLI `--import/--manual`; auth.json идентичен Node-выводу.
- **Фаза 2 — ядро.** sse.py, ds_client.py (AuthRequiredError 401/403/40002/40003, RateLimitError 429, UpstreamError),
  server.py (`/v1/models`, `/health`, `/v1/chat/completions` stream/non-stream, удаление чата в `finally`).
- **Фаза 3 — AuthManager.** Старт: auth.json → silent refresh; на auth-ошибке: single-flight refresh (lock + `_generation`)
  → эскалация в окно логина (`INTERACTIVE_LOGIN=1`) → drop с 401. Фоновый refresh каждые `REFRESH_INTERVAL_H=6`.
  Эндпоинты `/v1/auth/status|refresh|login`. Проверено с мусорным auth.json: цикл refresh в логах, 401 с инструкцией.
- **Фаза 4 — улучшения.** Reasoning: `thinking_enabled=True` для reasoner, THINK-дельты отдельно → `reasoning_content`
  в чанках и финальном ответе. Usage-оценка (estimate_tokens) в non-stream. Middleware `PROXY_API_KEY`
  (OPTIONS пропускается), CORS по `CORS_ORIGIN`. Retry/backoff на RateLimitError/UpstreamError (только до первого текста).
- **Фаза 5 — тесты.** `pytest`: **69 passed, 1 skipped** (live). Сьют поймал реальный баг: двойной `__aenter__` в complete().
- **Фаза 6 — упаковка.** README (Python), Dockerfile, .dockerignore, удаление Node-файлов и спайк-артефактов.

## 5. Фаза 7 — ручная e2e: ВЫПОЛНЕНО (2026-10-07), все проверки пройдены

Результаты прогона с живым аккаунтом:

1. ✅ `--login` → окно → вход → auth.json сохранён.
2. ✅ non-stream → 200 «pong»; stream → OpenAI-чанки.
3. ✅ `deepseek-reasoner` → 200, `reasoning_content` на месте.
4. ✅ Подмена токена на мусор + живой профиль → **startup silent refresh** вылечил токен до первого запроса, auth.json перезаписан свежим токеном, запрос 200.
5. ✅ Мусорный токен + пустой профиль → запрос → 40002 → silent refresh fail → **интерактивное окно** → вход → повтор → 200 «recovered» (целиком в логах: «Auth error… refresh + повтор / Silent refresh не помог / Открываю окно логина»).
6. ✅ Удаление чатов: endpoint `/api/v0/chat_session/delete` **подтверждён вручную** (create → delete → 200 code 0); в логе сервера «Чат … удалён». Обрыв стрима (`| head -2`) сервер переживает, следующий запрос 200.
7. ✅ `pytest --live` → **70 passed** (live PoW: реальный челлендж решён через wasmtime-порт).

Важные находки прогона:
- **Логирование**: INFO-логи пакета глушились под uvicorn → в `serve()` добавлен `logging.basicConfig(level=INFO)`.
- **Убийство серверов на Windows**: `kill $!` и Git Bash `pkill` НЕ убивают Windows-дочки — использовать PowerShell `Stop-Process` по фильтру CommandLine.
- Тестовый интерактив (`--login`/окно эскалации) не прогонять без присмотра — смоук-прогоны только с `INTERACTIVE_LOGIN=0` (иначе окно повиснет до таймаута 300с).

Осталось только человеческое: взглянуть в веб-UI DeepSeek, что в сайдбаре не копятся чаты от запросов прокси.

## 6. Ключевые решения / ограничения

- **auth.json v1 совместим со старой Node-версией** — файл и browser-profile общие (`~/.deepseek-free-api/`).
- **PoW glue** — посимвольный порт wbindgen-конвенций; кэш WASM в `~/.deepseek-free-api/sha3_wasm_bg.wasm` (при смене WASM править `config.DEEPSEEK_SHA3_WASM`).
- **Endpoint удаления чата недокументирован** — best-effort (сбой логируется, ответ не портится); env-override `DS_CHAT_DELETE_PATH`.
- **Дефолты безопасности:** HOST=`127.0.0.1`, входящий ключ прокси опционально (`PROXY_API_KEY`), CORS выключен.
- **Auto-login при старте сервера НЕ открывает окно** — сервер стартует в любом состоянии, статус в `/health`; при заданных `DS_EMAIL`/`DS_PASSWORD` старт пробует headless-логин (silent → креды), окно — только как fallback.
- **Порт по умолчанию 18632.**
- Тестовый интерактив (`--login`/окно эскалации) НЕЛЬЗЯ прогонять без присмотра — смоук-прогоны только с `INTERACTIVE_LOGIN=0` (иначе окно повиснет до таймаута 300с).

## 7. Env-переменные

| Переменная | Дефолт | Назначение |
|---|---|---|
| (все) | — | задаются через env или `.env` в корне проекта (env > `.env`); шаблон — `.env.example` |
| `PORT` / `HOST` | 18632 / 127.0.0.1 | адрес сервера |
| `PROXY_API_KEY` | — | входящие запросы требуют `Authorization: Bearer <key>` |
| `CORS_ORIGIN` | — | включить CORS для origin |
| `DELETE_CHAT` | 1 | удалять чат после ответа |
| `INTERACTIVE_LOGIN` | 1 | авто-открытие окна логина при протухании |
| `DS_EMAIL` / `DS_PASSWORD` | — | headless авто-логин по кредам (форма DeepSeek без окна), fallback — окно |
| `HEADLESS_LOGIN_TIMEOUT_S` | 60 | таймаут headless-логина, сек |
| `DEFAULT_MODEL` | deepseek-chat | модель по умолчанию (без thinking) при отсутствии/неизвестной модели в запросе |
| `RESPONSE_FORMAT` | text | non-stream: `text` = голый текст (text/plain, для TMS), `openai` = полный JSON |
| `REFRESH_INTERVAL_H` | 6 | фоновый silent refresh, часов |
| `RETRY_BACKOFF_S` | 2 | пауза повтора при 429/5xx |
| `DEBUG` | 0 | лог SSE-событий |
| `DEEPSEEK_FREE_AUTH_DIR` | ~/.deepseek-free-api | auth.json, browser-profile, кэш WASM |
| `DS_CHAT_DELETE_PATH` | /api/v0/chat_session/delete | endpoint удаления (не подтверждён) |

## 8. Разрешения Kimi Code для этого проекта

`.kimi-code/local.toml` — project-scope allow-правила для частых bash-команд
(`python*`/`*python.exe*`/`pip*`/`node*`/`npm*`/`npx*`/`curl*`/`kill*` + read-only хелперы), `kimi doctor config` — OK.
Файл в `.gitignore`. `rm*` и git-мутации НЕ разрешены (спрашиваем). Проверить после `/reload`.

## 9. Проверочные команды (быстрый прогон)

```bash
.venv/Scripts/python.exe -m pytest tests/ -q                                   # сьют
DEEPSEEK_FREE_AUTH_DIR="$PWD/tmp-auth" INTERACTIVE_LOGIN=0 PORT=18778 .venv/Scripts/python.exe -m deepseek_free_api
curl -s localhost:18778/health
curl -s -X POST localhost:18778/v1/chat/completions -H "Content-Type: application/json" \
  -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"hi"}]}'   # ожидание: 401 authentication_error без валидной сессии
```

## 10. Java-версия (Spring Boot 3.5.16)

**Статус: реализовано и проверено live (2026-10-11).** Полный порт Python-версии на
Java 21 / Spring Boot 3.5.16 в каталоге `java/`. Тестовый сьют: **134 теста зелёные**
(`cd java && mvn -U test`, 1 skipped — posix-права на Windows). Live-прогон против
реального DeepSeek: non-stream (text/openai), stream SSE, reasoner (мысли отделены),
UTF-8, удаление чатов, срабатывание PromptSanitizer — всё подтверждено.

**ВАЖНО про upstream:** в октябре 2026 DeepSeek сменил формат SSE-стрима —
контент фрагментов приходит дельтами `{"p":"response/fragments/-1/content",...}`
и корневыми `{"v":"..."}`, тип фрагмента (THINK/RESPONSE) известен только из
события создания фрагмента. Java-версия маршрутизирует дельты по типу последнего
фрагмента и исправна; **Python-версия (sse.py) на новом формате кладёт мысли
reasoner-модели в основной текст и теряет хвост ответа** — баг подтверждён
live-прогоном, в Java исправлен (SseEventParser), Python оставлен как есть.

Структура `java/`:

```
java/
├── pom.xml                          # Spring Boot 3.5.16, playwright 1.63.0, chicory 1.7.5
├── Dockerfile                       # multi-stage: maven-сборка → JRE 21 + chromium
├── README.md                        # сборка, запуск, CLI, конфиг, endpoints, privacy, Docker
└── src/main/java/com/nocodesolution/deepseek_free_api/
    ├── DeepseekFreeApiApplication   # точка входа: CLI-режимы или Spring Boot
    ├── cli/CliRunner                # --manual / --import / --login / --connect (без Spring-контекста)
    ├── config/                      # AppProperties (@ConfigurationProperties), DotenvEnvironmentPostProcessor,
    │                                #   WebConfig (CORS), RestTemplateConfig, ServiceConfig
    ├── controllers/ApiController    # /v1/chat/completions (SSE + non-stream), /v1/models, /health,
    │                                #   /v1/auth/status|refresh|login
    ├── dto/                         # ChatCompletionRequest, ChatMessage
    ├── browser/                     # BrowserAuthService (Playwright: окно, CDP-connect, headless по кредам)
    ├── services/                    # AuthManager, AuthStorage, DeepSeekClient, CompletionService,
    │                                #   MessageConverter, SseEventParser, PowSolver (Chicory AoT),
    │                                #   PowWasmLoader, PromptSanitizer
    └── support/ApiKeyFilter         # проверка PROXY_API_KEY
```

Ключевые отличия от Python-версии:

- **RestTemplate вместо httpx** — HTTP-клиент синхронный; SSE-стрим читается
  последовательно на виртуальных трейдах (`spring.threads.virtual.enabled=true`),
  async-контроллеры не нужны.
- **Chicory AoT вместо wasmtime** — PoW (sha3_wasm_bg.wasm) исполняется через
  Chicory с AoT-компиляцией (`com.dylibso.chicory:compiler`, AotMachine);
  интерпретатор Chicory ~в 200 раз медленнее, поэтому интерпретатор не вариант.
- **Playwright sync API** (1.63.0, `com.microsoft.playwright`) вместо Python async API.
- **PromptSanitizer — новая фича, в Python нет**: детерминированное (regex, без ML)
  маскирование секретов в исходящем prompt до отправки в DeepSeek — PEM-ключи,
  `password=/token=`-пары, Bearer, JWT, `sk-...`, AWS-ключи, длинные токены,
  IPv4/IPv6, email, URL + кастомные шаблоны (`PRIVACY_MASK_PATTERNS`).
  В лог — только метаданные замены, не значения. Отключение: `PRIVACY_MASK_SECRETS=false`.
- **Конфигурация** — `application.properties` + Spring relaxed binding + `.env`
  из текущей директории (DotenvEnvironmentPostProcessor, паритет Python
  `_load_dotenv`: env имеет приоритет над `.env`). Имена env частично отличаются
  от Python (`DELETE_CHAT` → `DEEPSEEK_DELETE_CHAT` и т.п., таблица — в java/README.md);
  совпадают: `PROXY_API_KEY`, `CORS_ORIGIN`, `DS_EMAIL`, `DS_PASSWORD`,
  `HEADLESS_LOGIN_TIMEOUT_S`, `DEEPSEEK_SHA3_WASM` + новые `PRIVACY_MASK_SECRETS`,
  `PRIVACY_MASK_PATTERNS`.
- **auth.json v1 и `~/.deepseek-free-api/` совместимы с Python-версией** — сессия
  общая, можно получать сессию одной версией и работать другой.
