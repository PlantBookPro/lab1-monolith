# Сборка
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY gradlew ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts ./
RUN ./gradlew --no-daemon -q dependencies > /dev/null 2>&1 || true
COPY src/ src/
RUN ./gradlew --no-daemon -q bootJar && mkdir -p build/models

# Рантайм
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/build/libs/*.jar app.jar
# Модель классификатора (ADR-009): скачивается при сборке; offline-сборка
# оставляет каталог пустым — задания честно уходят в RETRY (README «Модерация»)
COPY --from=build /build/build/models/ build/models/
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
