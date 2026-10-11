# DeepSeek Free API (Java / Spring Boot)

OpenAI-совместимый прокси поверх web-аккаунта DeepSeek (Spring Boot 3.5.16,
Java 21): сервер + CLI `--login`/`--connect`/`--import`/`--manual`,
auth.json v1, режим маскирования секретов в исходящих запросах
(PromptSanitizer). Общее описание и быстрый старт — в корневом
[README.md](../README.md).

## Требования

- Java 21+
- Maven 3.9+

## Сборка и запуск

```bash
mvn package          # сборка (+ тесты)
mvn -DskipTests package   # сборка без тестов

java -jar target/deepseek-free-api-0.0.1-SNAPSHOT.jar
```

Сервер слушает `0.0.0.0:18632` (по умолчанию).

## CLI-режимы (без старта сервера)

```bash
java -jar target/deepseek-free-api-0.0.1-SNAPSHOT.jar --login      # окно браузера → вход в DeepSeek
java -jar target/deepseek-free-api-0.0.1-SNAPSHOT.jar --connect 9222  # забрать сессию из запущенного Chrome (CDP)
java -jar target/deepseek-free-api-0.0.1-SNAPSHOT.jar --import cookies.json "<userToken>"  # ручной импорт
java -jar target/deepseek-free-api-0.0.1-SNAPSHOT.jar --manual     # инструкция по экспорту из DevTools
```

CLI-режимы читают env напрямую: `DS_EMAIL`, `DS_PASSWORD`,
`DEEPSEEK_FREE_AUTH_DIR`, `HEADLESS_LOGIN_TIMEOUT_S`.
Если заданы `DS_EMAIL`/`DS_PASSWORD`, `--login` сначала пробует headless-логин
по кредам, при неудаче — открывает окно.

Сессия (auth.json v1), browser-profile и кэш
WASM лежат в `~/.deepseek-free-api/` (переопределяется `AUTH_DIR` /
`auth.dir`).

## Конфигурация

Настройки — через `application.properties`, env-переменные или `.env` в текущей
рабочей директории. Приоритет: реальные env-переменные > `.env` > дефолты
(`.env` ничего не перезаписывает). Имена env — Spring relaxed binding
(`proxy.api-key` ↔ `PROXY_API_KEY`) — см. таблицу.

| Свойство | Env-переменная | Дефолт | Назначение |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | 18632 | порт сервера |
| `server.address` | `SERVER_ADDRESS` | 0.0.0.0 | адрес bind |
| `proxy.api-key` | `PROXY_API_KEY` | — | входящие запросы требуют `Authorization: Bearer <key>`; пусто = без ключа |
| `cors.origin` | `CORS_ORIGIN` | — | включить CORS для origin; пусто = выключен |
| `deepseek.base-url` | `DEEPSEEK_BASE_URL` | https://chat.deepseek.com | базовый URL DeepSeek |
| `deepseek.app-version` | `DEEPSEEK_APP_VERSION` | 2.0.0 | заголовок x-app-version |
| `deepseek.completion-path` | `DEEPSEEK_COMPLETION_PATH` | /api/v0/chat/completion | endpoint completion |
| `deepseek.chat-delete-path` | `DEEPSEEK_CHAT_DELETE_PATH` | /api/v0/chat_session/delete | endpoint удаления чата |
| `deepseek.delete-chat` | `DEEPSEEK_DELETE_CHAT` | true | удалять чат после ответа |
| `deepseek.default-model` | `DEEPSEEK_DEFAULT_MODEL` | deepseek-chat | модель по умолчанию |
| `deepseek.response-format` | `DEEPSEEK_RESPONSE_FORMAT` | text | non-stream формат: `text` (голый текст) / `openai` (полный JSON) |
| `deepseek.retry-backoff-s` | `DEEPSEEK_RETRY_BACKOFF_S` | 2 | пауза повтора при 429/5xx |
| `deepseek.log-sse` | `DEEPSEEK_LOG_SSE` | false | дамп SSE-событий в лог |
| `deepseek.connect-timeout-ms` | `DEEPSEEK_CONNECT_TIMEOUT_MS` | 30000 | connect-таймаут |
| `deepseek.read-timeout-ms` | `DEEPSEEK_READ_TIMEOUT_MS` | 0 | read-таймаут; 0 = бесконечный SSE-стрим |
| `deepseek.pow-wasm-url` | `DEEPSEEK_SHA3_WASM` | URL fe-static.deepseek.com | URL sha3_wasm_bg.wasm для кэша в `<auth.dir>` |
| `auth.dir` | `AUTH_DIR` | ~/.deepseek-free-api | auth.json, browser-profile, кэш WASM |
| `auth.interactive-login` | `AUTH_INTERACTIVE_LOGIN` | true | авто-открытие окна логина при протухании |
| `auth.refresh-interval-h` | `AUTH_REFRESH_INTERVAL_H` | 6 | фоновый silent refresh, часов |
| `auth.email` | `AUTH_EMAIL` | — | креды для headless авто-логина (CLI читает `DS_EMAIL`) |
| `auth.password` | `AUTH_PASSWORD` | — | креды для headless авто-логина (CLI читает `DS_PASSWORD`) |
| `auth.headless-login-timeout-s` | `AUTH_HEADLESS_LOGIN_TIMEOUT_S` | 60 | таймаут headless-логина, сек (CLI читает `HEADLESS_LOGIN_TIMEOUT_S`) |
| `privacy.mask-secrets` | `PRIVACY_MASK_SECRETS` | true | маскировать секреты в исходящем prompt |
| `privacy.mask-patterns` | `PRIVACY_MASK_PATTERNS` | — | доп. regex-шаблоны через запятую |

Пример `.env` — список переменных из таблицы выше, по одной на строку
(`KEY=value`). Реальные env-переменные имеют приоритет над `.env`.

## Endpoints

| Метод | Путь | Описание |
|---|---|---|
| POST | `/v1/chat/completions` | OpenAI chat completions; `stream: true` → SSE-чанки, `stream: false` → text/plain или JSON (см. `deepseek.response-format`) |
| GET | `/v1/models` | список моделей (`deepseek-chat`, `deepseek-reasoner`, `deepseek-r1`) |
| GET | `/health` | статус сервера и авторизации |
| GET | `/v1/auth/status` | статус сессии DeepSeek |
| POST | `/v1/auth/refresh` | принудительный silent refresh токена |
| POST | `/v1/auth/login` | принудительный (интерактивный) логин |

## Маскирование секретов (privacy)

Перед отправкой в DeepSeek prompt проходит через `PromptSanitizer`
(детерминированные regex-детекторы, без ML). По умолчанию включено
(`PRIVACY_MASK_SECRETS=true`). Маскируются: PEM-блоки приватных ключей,
пары `password=/token=/api_key=...` (значение → `***`), Bearer-токены, JWT,
ключи `sk-...`, AWS `AKIA...`, длинные токены (32+ символов), IPv4/IPv6,
email, URL. В лог пишутся только метаданные замены (детектор, позиция, длина) —
сам секрет не логируется.

Пример:

```
вход:  "мой пароль: password=Sup3rSecret! и ключ sk-proj-AbCdEfGhIjKlMnOpQrSt"
выход: "мой пароль: password=*** и ключ ***api-key***"
```

Свои шаблоны — через запятую в `PRIVACY_MASK_PATTERNS`:

```bash
PRIVACY_MASK_PATTERNS='internal\.corp\.\w+,SECRET-\d+'
```

Невалидный regex — ошибка на старте приложения (fail-fast).

## Docker

```bash
cd java
docker build -t deepseek-free-api .
docker run -p 18632:18632 -v deepseek-auth:/data deepseek-free-api
```

- `/data` — volume с сессией (`auth.json`, browser-profile, кэш WASM);
  пересоздание контейнера сессию не теряет.
- Образ собирается multi-stage: Maven-сборка → JRE 21 + playwright chromium
  (со всеми системными зависимостями).
- В контейнере интерактивный логин отключён (`AUTH_INTERACTIVE_LOGIN=false`):
  авторизацию делай снаружи (`--login`/`--import` на хосте в тот же том `/data`)
  или по кредам (`AUTH_EMAIL`/`AUTH_PASSWORD`).
