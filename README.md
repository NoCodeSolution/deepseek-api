# DeepSeek Free API (Python)

**Бесплатный OpenAI-совместимый API через DeepSeek.**
Без API-ключей DeepSeek — только браузерная авторизация.
Нужен аккаунт DeepSeek (регистрация бесплатная).

Переписано с Node.js на Python: автоматическое обновление протухающего токена,
режим «1 вопрос = 1 чат» с удалением чатов, reasoning-вывод для `deepseek-reasoner`,
корректные OpenAI-ошибки, защищённые дефолты.

## Как это работает

Сервис сохраняет браузерную сессию DeepSeek (cookies + токен) и проксирует запросы
к web API DeepSeek в формате OpenAI. Сессия хранится в `~/.deepseek-free-api/`
(формат совместим со старой Node-версией).

**Автообновление токена.** Когда DeepSeek отвечает ошибкой авторизации, прокси
сам обновляет токен из сохранённого браузерного профиля (тихо, headless) и повторяет
запрос. Если профиль тоже протух — открывается окно повторного входа. Ручной
перелогин нужен только если умерла сама сессия аккаунта.

**Режим чатов: 1 вопрос = 1 чат.** Каждый запрос создаёт новый чат DeepSeek,
после ответа чат удаляется — в веб-интерфейсе не копится мусор.
Отключить удаление: `DELETE_CHAT=0`.

**Совместим с любым OpenAI-клиентом:** Cursor, Continue.dev, Aider, OpenCode,
Claude Code, кастомные скрипты.

---

## Быстрый старт

### 1. Установка

```bash
git clone <ссылка_на_репо> deepseek-free-api
cd deepseek-free-api

python -m venv .venv
# Windows:
.venv\Scripts\pip install -e .
.venv\Scripts\playwright install chromium
# Linux/macOS:
# .venv/bin/pip install -e .
# .venv/bin/playwright install chromium
```

> Нужен Python 3.10+. Google Chrome опционален (используется при наличии,
> иначе bundled Chromium).

### 2. Получить сессию DeepSeek (один из способов)

**A. Логин через окно (проще всего):**

```bash
deepseek-free-api --login
```

Откроется окно браузера — войди в DeepSeek любым способом. Окно закроется само.

**B. Из твоего Chrome (если уже залогинен):**

```bash
# 1. Закрой Chrome полностью
# 2. Запусти заново с флагом:
chrome --remote-debugging-port=9222
# 3. Залогинься на https://chat.deepseek.com (если ещё нет)
# 4. Забери сессию:
deepseek-free-api --connect
```

**C. Ручной импорт (без браузерной автоматики):**

```bash
deepseek-free-api --manual   # инструкция по экспорту из DevTools
deepseek-free-api --import cookies.json "<userToken>"
```

### 3. Запуск

```bash
deepseek-free-api            # http://localhost:18632
```

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
| POST | `/v1/auth/login` | Принудительное окно входа (при заданных `DS_EMAIL`/`DS_PASSWORD` сначала пробует headless) |

> **Формат ответа:** по умолчанию non-stream возвращает **голый текст** ответа модели
> (`Content-Type: text/plain`) — для TMS и других простых потребителей.
> Полный OpenAI JSON: `RESPONSE_FORMAT=openai`. Ошибки всегда JSON с HTTP-кодом.
> Модель в запросе необязательна: без неё (или при неизвестной) используется
> `DEFAULT_MODEL` (по умолчанию `deepseek-chat`, без размышлений).

## Если авторизация протухла

Обычно ничего делать не нужно — прокси сам обновит токен при первом запросе.
Если заданы `DS_EMAIL`/`DS_PASSWORD` — при мёртвой сессии прокси сам залогинится
headless (без окна); при капче/неудаче откроется окно. Ручной вход:

```bash
deepseek-free-api --login
```

## Переменные окружения

Все настройки задаются переменными окружения **или** файлом `.env` в корне проекта
(см. `.env.example`; реальные переменные окружения имеют приоритет над `.env`).
`.env` добавлен в `.gitignore` — креды не попадут в репозиторий.

| Переменная | Дефолт | Назначение |
|---|---|---|
| `PORT` / `HOST` | `18632` / `127.0.0.1` | Адрес сервера |
| `PROXY_API_KEY` | — | Если задан — входящие запросы требуют `Authorization: Bearer <key>` |
| `CORS_ORIGIN` | — | Разрешить CORS для указанного origin |
| `DELETE_CHAT` | `1` | Удалять чат DeepSeek после ответа |
| `INTERACTIVE_LOGIN` | `1` | Разрешать авто-открытие окна логина при протухании |
| `DS_EMAIL` / `DS_PASSWORD` | — | Креды аккаунта DeepSeek: headless авто-логин без окна (при старте и при протухании); при неудаче — fallback в окно |
| `HEADLESS_LOGIN_TIMEOUT_S` | `60` | Таймаут headless-логина по кредам, секунд |
| `DEFAULT_MODEL` | `deepseek-chat` | Модель по умолчанию, если в запросе не задана или неизвестна (без размышлений) |
| `RESPONSE_FORMAT` | `text` | Формат non-stream ответа: `text` — голый текст (`text/plain`, для TMS), `openai` — полный OpenAI JSON |
| `REFRESH_INTERVAL_H` | `6` | Период фонового обновления токена, часов |
| `RETRY_BACKOFF_S` | `2` | Пауза повтора при 429/5xx от DeepSeek |
| `DEBUG` | `0` | Логировать SSE-события |
| `DEEPSEEK_FREE_AUTH_DIR` | `~/.deepseek-free-api` | Каталог auth.json и профиля браузера |
| `DS_CHAT_DELETE_PATH` | `/api/v0/chat_session/delete` | Endpoint удаления чата (недокументирован DeepSeek) |

## Docker

```bash
docker build -t deepseek-free-api .
# Контейнер без дисплея: сессию получаем на хосте через --import,
# затем пробрасываем каталог авторизации:
docker run -p 18632:18632 -v ~/.deepseek-free-api:/root/.deepseek-free-api deepseek-free-api
```

## Тесты

```bash
pip install -e ".[dev]"
pytest                 # офлайн-сьют
pytest --live          # + live-тесты (нужна живая сессия DeepSeek)
```

## Ограничения

- Лимиты DeepSeek на запросы с одной сессии (~20–30/мин) — при 429 прокси делает один повтор с паузой.
- Не все фичи web-версии доступны через API-формат (файлы, поиск).
- Сессию нужно периодически подтверждать (раз в несколько дней) — прокси обновляет её сам, пока жив аккаунт.
- Endpoint удаления чата не задокументирован DeepSeek: если в веб-UI чаты перестанут удаляться, задайте актуальный путь через `DS_CHAT_DELETE_PATH`.

## Требования

- **Python** 3.10+
- **Google Chrome** или Chromium (`playwright install chromium`)
- **Аккаунт DeepSeek** — https://chat.deepseek.com
