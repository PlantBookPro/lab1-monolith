# Сборка
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q -DskipTests dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -DskipTests package && mkdir -p target/models

# Рантайм
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/*.jar app.jar
# Модель классификатора (ADR-009): скачивается при сборке; offline-сборка
# оставляет каталог пустым — задания честно уходят в RETRY (README «Модерация»)
COPY --from=build /build/target/models/ target/models/
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
