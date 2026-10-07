FROM python:3.12-slim

WORKDIR /app

COPY pyproject.toml README.md ./
COPY src ./src

RUN pip install --no-cache-dir . \
    && playwright install --with-deps chromium

EXPOSE 18632

# В контейнере нет дисплея: сессия DeepSeek готовится на хосте
# (deepseek-free-api --login / --import) и пробрасывается volume-ом:
#   docker run -p 18632:18632 -v ~/.deepseek-free-api:/root/.deepseek-free-api deepseek-free-api
CMD ["deepseek-free-api"]
