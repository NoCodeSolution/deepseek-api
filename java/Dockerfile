# ---------- stage 1: сборка ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# сначала pom — слой с зависимостями кэшируется отдельно от исходников
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

# ---------- stage 2: runtime ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /build/target/deepseek-free-api-*.jar /app/app.jar

# Браузер chromium для playwright (интерактивный логин/обновление сессии).
# Зависимости в Boot fat-jar вложенные (BOOT-INF/lib), поэтому CLI playwright
# запускаем через Spring Boot JarLauncher с переопределением главного класса.
# Артефакт playwright 1.63.0 сам тянет driver-bundle — отдельная зависимость не нужна.
# --with-deps ставит системные библиотеки через apt (образ Ubuntu-based).
RUN java -cp /app/app.jar -Dloader.main=com.microsoft.playwright.CLI \
        org.springframework.boot.loader.launch.JarLauncher install --with-deps chromium \
    && rm -rf /var/lib/apt/lists/*

# ENV-дефолты (relaxed binding: AUTH_DIR → auth.dir и т.д.)
# /data — auth.json, browser-profile, кэш WASM (смонтируй volume, чтобы сессия переживала пересоздание)
ENV AUTH_DIR=/data \
    SERVER_PORT=18632 \
    AUTH_INTERACTIVE_LOGIN=false

VOLUME /data
EXPOSE 18632

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
