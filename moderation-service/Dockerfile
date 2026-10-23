FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY . .
RUN ./gradlew --no-daemon -q -Dorg.gradle.internal.http.socketTimeout=120000 -Dorg.gradle.internal.http.connectionTimeout=120000 :moderation-service:bootJar :moderation-service:downloadModerationModel

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/moderation-service/build/libs/*.jar app.jar
COPY --from=build /build/moderation-service/build/models/ /app/models/
ENV MODERATION_MODEL_PATH=/app/models/mobilenetv2-1.0.onnx
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
