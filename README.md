# DeepSeek Free API (Java / Spring Boot)

**Бесплатный OpenAI-совместимый API через DeepSeek.**
Без API-ключей DeepSeek — только браузерная авторизация.
Нужен аккаунт DeepSeek (регистрация бесплатная).

Реализация на **Java 21 / Spring Boot 3.5.16**: сервер + CLI
`--login`/`--connect`/`--import`/`--manual`, автоматическое обновление
протухающего токена, режим «1 вопрос = 1 чат» с удалением чатов,
reasoning-вывод для `deepseek-reasoner`, корректные OpenAI-ошибки,
маскирование секретов в исходящих prompt (`PRIVACY_MASK_SECRETS`).

## Как это работает

Сервис сохраняет браузерную сессию DeepSeek (cookies + токен) и проксирует запросы
к web API DeepSeek в формате OpenAI. Сессия хранится в `~/.deepseek-free-api/`.

**Автообновление токена.** Когда DeepSeek отвечает ошибкой авторизации, прокси
сам обновляет токен из сохранённого браузерного профиля (тихо, headless) и повторяет
запрос. Если профиль тоже протух — открывается окно повторного входа. Ручной
перелогин нужен только если умерла сама сессия аккаунта.

**Режим чатов: 1 вопрос = 1 чат.** Каждый запрос создаёт новый чат DeepSeek,
после ответа чат удаляется — в веб-интерфейсе не копится мусор.
Отключить удаление: `DEEPSEEK_DELETE_CHAT=false`.

**Совместим с любым OpenAI-клиентом:** Cursor, Continue.dev, Aider, OpenCode,
Claude Code, кастомные скрипты.

---

## Быстрый старт

### 1. Сборка

```bash
mvn package
```

> Нужны Java 21+ и Maven 3.9+. Google Chrome опционален (используется при наличии,
> иначе bundled Chromium).

### 2. Получить сессию DeepSeek (один из способов)

**A. Логин через окно (проще всего):**

```bash
java -jar target/deepseek-free-api-0.0.1-SNAPSHOT.jar --login
```

Откроется окно браузера — войди в DeepSeek любым способом. Окно закроется само.

**B. Из твоего Chrome (если уже залогинен):**

```bash
# 1. Закрой Chrome полностью
# 2. Запусти заново с флагом:
chrome --remote-debugging-port=9222
# 3. Залогинься на https://chat.deepseek.com (если ещё нет)
# 4. Забери сессию:
java -jar target/deepseek-free-api-0.0.1-SNAPSHOT.jar --connect 9222
```

**C. Ручной импорт (без браузерной автоматики):**

```bash
java -jar target/deepseek-free-api-0.0.1-SNAPSHOT.jar --manual   # инструкция по экспорту из DevTools
java -jar target/deepseek-free-api-0.0.1-SNAPSHOT.jar --import cookies.json "<userToken>"
```

Если заданы `DS_EMAIL`/`DS_PASSWORD`, `--login` сначала пробует headless-логин
по кредам, при неудаче — открывает окно. Если DeepSeek требует подтверждение аккаунта,
headless-режим ждёт ввод полученного ранее кода в консоли, не запрашивая новый код через
**Send code**. После входа постоянный `browser-profile` переиспользуется для тихого
обновления сессии. CLI-режимы также читают `DEEPSEEK_FREE_AUTH_DIR` и `HEADLESS_LOGIN_TIMEOUT_S`.

### 3. Запуск

```bash
java -jar target/deepseek-free-api-0.0.1-SNAPSHOT.jar   # http://localhost:18632
```

Сессия (auth.json v1) и browser-profile лежат в `~/.deepseek-free-api/`
(переопределяется `AUTH_DIR` / `auth.dir`).

---

## Куда вставлять

### curl (проверка)

```bash
curl http://localhost:18632/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"Привет!"}],"stream":false}'
```

### OpenCode

```yaml
model: deepseek-chat
provider:
  id: deepseek-free
  url: http://localhost:18632/v1
  key: sk-dummy
```

### Cursor

Settings → Models → Add Custom Model:
- **Name:** `deepseek-chat`
- **Endpoint:** `http://localhost:18632/v1`
- **Key:** любой (например `sk-dummy`)

### Aider

```bash
aider --model openai/deepseek-chat --openai-api-base http://localhost:18632/v1 --openai-api-key sk-dummy
```

---

## Модели

| ID | Описание |
|---|---|
| `deepseek-chat` | DeepSeek V3 / V4 (обычный чат) |
| `deepseek-reasoner` | DeepSeek R1 (thinking + `reasoning_content` в ответе) |
| `deepseek-r1` | Алиас deepseek-reasoner |

## Endpoints

| Метод | Путь | Назначение |
|---|---|---|
| POST | `/v1/chat/completions` | OpenAI-совместимый чат (stream/non-stream) |
| GET | `/v1/models` | Список моделей |
| GET | `/health` | Состояние + статус авторизации |
| GET | `/v1/auth/status` | Детали авторизации |
| POST | `/v1/auth/refresh` | Тихое обновление токена из профиля |
| POST | `/v1/auth/login` | Принудительное окно входа (при заданных кредах сначала пробует headless) |

> **Формат ответа:** по умолчанию non-stream возвращает **голый текст** ответа модели
> (`Content-Type: text/plain`) — для простых потребителей.
> Полный OpenAI JSON: `DEEPSEEK_RESPONSE_FORMAT=openai`. Ошибки всегда JSON с HTTP-кодом.
> Модель в запросе необязательна: без неё (или при неизвестной) используется
> `deepseek-chat` (без размышлений).

## Если авторизация протухла

Обычно ничего делать не нужно — прокси сам обновит токен при первом запросе.
Если заданы `AUTH_EMAIL`/`AUTH_PASSWORD` — при мёртвой сессии прокси сам залогинится
headless (без окна); при капче/неудаче откроется окно. Ручной вход:

```bash
java -jar target/deepseek-free-api-0.0.1-SNAPSHOT.jar --login
```

## Конфигурация

Настройки — через `src/main/resources/application.properties` или
env-переменные. Имена env — Spring relaxed binding
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
| `deepseek.wasm-path` | `DEEPSEEK_WASM_PATH` | — | путь к внешнему `sha3_wasm_bg.wasm`; пусто = встроенный из classpath |
| `auth.dir` | `AUTH_DIR` | ~/.deepseek-free-api | auth.json, browser-profile |
| `auth.interactive-login` | `AUTH_INTERACTIVE_LOGIN` | true | авто-открытие окна логина при протухании |
| `auth.refresh-interval-h` | `AUTH_REFRESH_INTERVAL_H` | 6 | фоновый silent refresh, часов |
| `auth.email` | `AUTH_EMAIL` | — | креды для headless авто-логина (CLI читает `DS_EMAIL`) |
| `auth.password` | `AUTH_PASSWORD` | — | креды для headless авто-логина (CLI читает `DS_PASSWORD`) |
| `auth.headless-login-timeout-s` | `AUTH_HEADLESS_LOGIN_TIMEOUT_S` | 60 | таймаут headless-логина, сек (CLI читает `HEADLESS_LOGIN_TIMEOUT_S`) |
| `privacy.mask-secrets` | `PRIVACY_MASK_SECRETS` | true | маскировать секреты в исходящем prompt |
| `privacy.mask-patterns` | `PRIVACY_MASK_PATTERNS` | — | доп. regex-шаблоны через запятую |

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
docker build -t deepseek-free-api .
docker run -p 18632:18632 -v deepseek-auth:/data deepseek-free-api
```

- `/data` — volume с сессией (`auth.json`, browser-profile);
  пересоздание контейнера сессию не теряет.
- Образ собирается multi-stage: Maven-сборка → JRE 21 + playwright chromium
  (со всеми системными зависимостями).
- В контейнере интерактивный логин отключён (`AUTH_INTERACTIVE_LOGIN=false`):
  авторизацию делай снаружи (`--login`/`--import` на хосте в тот же том `/data`)
  или по кредам (`AUTH_EMAIL`/`AUTH_PASSWORD`).

## Тесты

```bash
mvn test
```

## PoW WASM

Для решения PoW-челленджа DeepSeek приложение использует WASM-модуль
`sha3_wasm_bg.wasm`. По умолчанию берётся встроенная копия из classpath
(`src/main/resources/sha3_wasm_bg.wasm`), поэтому интернет не нужен.

Если нужно подменить файл (например, DeepSeek обновил алгоритм), задайте
`DEEPSEEK_WASM_PATH=/path/to/sha3_wasm_bg.wasm`. Пустое значение — использовать
встроенный ресурс.

Где взять новый файл: в ответах web-версии чата ищите запрос к `fe-static.deepseek.com`,
файл вида `sha3_wasm_bg.<hash>.wasm`. Актуальный URL на момент сборки:
`https://fe-static.deepseek.com/chat/static/sha3_wasm_bg.7b9ca65ddd.wasm`.

## Ограничения

- Лимиты DeepSeek на запросы с одной сессии (~20–30/мин) — при 429 прокси делает один повтор с паузой.
- Не все фичи web-версии доступны через API-формат (файлы, поиск).
- Сессию нужно периодически подтверждать (раз в несколько дней) — прокси обновляет её сам, пока жив аккаунт.
- Endpoint удаления чата не задокументирован DeepSeek: если в веб-UI чаты перестанут удаляться, задайте актуальный путь через `DEEPSEEK_CHAT_DELETE_PATH`.

## Требования

- **Java** 21+, **Maven** 3.9+
- **Google Chrome** или Chromium (для браузерной авторизации)
- **Аккаунт DeepSeek** — https://chat.deepseek.com
