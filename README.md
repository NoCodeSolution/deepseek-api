# DeepSeek Free API (Java / Spring Boot)

**Бесплатный OpenAI-совместимый API через DeepSeek.**
Без API-ключей DeepSeek — только браузерная авторизация.
Нужен аккаунт DeepSeek (регистрация бесплатная).

Реализация на **Java 21 / Spring Boot 3.5.16** — в каталоге [`java/`](java/).
Автоматическое обновление протухающего токена, режим «1 вопрос = 1 чат»
с удалением чатов, reasoning-вывод для `deepseek-reasoner`, корректные
OpenAI-ошибки, маскирование секретов в исходящих prompt (`PRIVACY_MASK_SECRETS`).

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
cd java
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

### 3. Запуск

```bash
java -jar target/deepseek-free-api-0.0.1-SNAPSHOT.jar   # http://localhost:18632
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

## Конфигурация, Docker, маскирование секретов

Полная таблица настроек (env / `application.properties`), Docker-запуск и режим
маскирования секретов (PromptSanitizer) — в [java/README.md](java/README.md).

## Тесты

```bash
cd java
mvn test
```

## Ограничения

- Лимиты DeepSeek на запросы с одной сессии (~20–30/мин) — при 429 прокси делает один повтор с паузой.
- Не все фичи web-версии доступны через API-формат (файлы, поиск).
- Сессию нужно периодически подтверждать (раз в несколько дней) — прокси обновляет её сам, пока жив аккаунт.
- Endpoint удаления чата не задокументирован DeepSeek: если в веб-UI чаты перестанут удаляться, задайте актуальный путь через `DEEPSEEK_CHAT_DELETE_PATH`.

## Требования

- **Java** 21+, **Maven** 3.9+
- **Google Chrome** или Chromium (для браузерной авторизации)
- **Аккаунт DeepSeek** — https://chat.deepseek.com
