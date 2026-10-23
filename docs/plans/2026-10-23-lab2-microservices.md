# Lab 2: Plant Arena Microservices — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decompose the Plant Arena DDD monolith into 10 microservices (7 domain + Eureka + Config Server + API Gateway) in a single Gradle monorepo on branch `lab2`, replacing in-process ACL adapters with Feign + Circuit Breaker, in-process events with HTTP delivery, and making geo-service fully reactive (R2DBC) and tournaments-service Reactor + Spring Data JPA.

**Architecture:** Each bounded context becomes an independently deployable Spring Boot service with its own PostgreSQL database and Flyway history. Cross-context calls go through internal REST endpoints (former `api` interfaces) consumed by Feign clients behind the consumer's existing hexagonal ports. Eureka provides discovery, Config Server (native/classpath repo) provides centralized config, Spring Cloud Gateway routes all external traffic. Domain/application layers are copied essentially unchanged; only adapters and wiring are new.

**Tech Stack:** Java 21, Gradle 9.3.1 Kotlin DSL (multi-module), Spring Boot 4.0.8, Spring Cloud 2025.1.4, Spring Cloud Gateway Server WebFlux 5.0.4, OpenFeign 5.0.4, Resilience4j (via spring-cloud-circuitbreaker), Spring Data JPA (tournaments), Spring Data R2DBC (geo), Flyway, Testcontainers, ONNX Runtime 1.20.0.

## Global Constraints

- Branch: `lab2` (created from `main`). Monolith code is moved to `monolith-reference/` during the rebuild and **deleted after final verification** (it remains on `main`).
- Package names are preserved: each service keeps `com.plantarena.<context>.*`. New code follows the same hexagonal conventions (`api`, `application/port`, `adapter/in`, `adapter/out`).
- External REST API contracts (paths, request/response JSON, headers `X-Demo-User-Id`, `X-Guest-Token`, error `ApiError` shape) are **unchanged** — the Bruno collection `PlantArena API` must keep working against the Gateway on `:8080`.
- Domain invariants, status machines, and all domain/application unit tests are preserved. Cross-context E2E ITs from the monolith are dropped (replaced by per-service ITs + docker smoke test).
- One PostgreSQL 17 container with **7 databases** (identity, media, plants, moderation, tournaments, geo, feed) — one DB per service. Schema-per-context is preserved inside each DB (migrations unchanged).
- Ports: gateway 8080, eureka 8761, config 8888, identity 8081, media 8082, plants 8083, moderation 8084, geo 8085, tournaments 8086, feed 8087.
- Infra services (eureka, config, gateway) use **local** `application.yml` only (no config-server import — avoids bootstrap circular dependency). Domain services use `optional:configserver:` import locally and plain `configserver:` in docker.
- Every task ends with `./gradlew <module>:build` (or documented verification) green and a commit.

## Verified Facts (do not re-verify)

- Spring Boot 4 starters: `spring-boot-starter-webmvc`, `spring-boot-starter-jdbc`, `spring-boot-starter-data-jpa`, `spring-boot-starter-webflux`, `spring-boot-starter-data-r2dbc` (all exist at 4.0.8).
- Spring Cloud 2025.1.4 BOM is built against Boot 4.0.9 → compatible with 4.0.8. Artifacts: `spring-cloud-starter-netflix-eureka-server/client`, `spring-cloud-starter-config`, `spring-cloud-config-server`, `spring-cloud-starter-openfeign`, `spring-cloud-starter-circuitbreaker-resilience4j`.
- Gateway starter is **`spring-cloud-starter-gateway-server-webflux`** (old `spring-cloud-starter-gateway` does not exist in 2025.1.x). Route property namespace is **`spring.cloud.gateway.server.webflux.routes`** and discovery locator is `spring.cloud.gateway.server.webflux.discovery.locator.enabled`.
- Versions managed by Boot BOM (no explicit version needed): postgresql, flyway, r2dbc-postgresql, testcontainers, awaitility, lombok, spring-web/context. Explicit versions only: springdoc 3.1.1, archunit 1.5.0, onnxruntime 1.20.0.
- Monolith facts (from codebase exploration): all repositories are `JdbcClient`-based; auth is `X-Demo-User-Id` header resolved via identity's `ResolveActorUseCase` (dev/test only, ADR-005); events are in-process Spring `ApplicationEventPublisher` behind the `IntegrationEventPublisher` port; per-context Flyway histories already exist under `src/main/resources/db/migration/<context>/`; no cross-schema FKs.

## Key Design Decisions (documented in docs/domain/adr/ in Task 16)

1. **"Reactive JPA repository" is technically impossible** (Spring Data JPA is blocking; `ReactiveCrudRepository` exists only for R2DBC). tournaments-service implements the requirement as: **WebFlux (Netty) reactive web tier + Reactor `Mono`/`Flux` controllers wrapping blocking use-cases via `Schedulers.boundedElastic()` + Spring Data JPA persistence for the `Tournament` and `Tag` aggregates** (JPA entities in `adapter/out/persistence/jpa/`, domain objects unchanged). Other tournaments aggregates keep their `JdbcClient` repositories (they participate in `JpaTransactionManager` transactions on the same `DataSource`). → ADR-014.
2. **geo-service is fully reactive**: reactive repository port (`Mono`/`Flux`), Spring Data R2DBC + `DatabaseClient` persistence, WebFlux controller, Flyway runs via JDBC side-channel (`spring.flyway.url` + postgresql JDBC driver). → ADR-014.
3. **Events over HTTP** (lab simplification per spec): publishers keep the `IntegrationEventPublisher` port; an `@TransactionalEventListener(AFTER_COMMIT)` relay POSTs to the consumer via Feign (log-on-failure). plants→moderation `PlantSubmitted` is replaced by **moderation polling** plants for `PENDING` plants (idempotent by `findLatestByPlantId`) — more robust than fire-and-forget. `PlantDied`, `TournamentStarted/Finished`, `EntryEliminated`, `InvitationCreated` have no consumers → logged only. → ADR-015.
4. **Auth**: every service resolves `X-Demo-User-Id` itself. identity-service resolves locally (existing `ResolveActorUseCase`); other services call `GET /internal/actors/{userId}` on identity-service via Feign (roles always from identity DB). Internal (`/internal/**`) endpoints are unauthenticated (trusted network); the Gateway does not route `/internal/**` so they are unreachable externally. JWT stays lab 3 (ADR-005). → ADR-016.
5. **Shared kernel**: `shared-lib` (pure Java: `CurrentActor`, `AppRole`, `ApiError`, `PaginationParams`, events, exceptions), `shared-web` (servlet: `TraceIdFilter`, `ApiExceptionHandler`, `SharedWebConfig` — imported only by MVC services), `shared-webflux` (reactive: `TraceIdWebFilter`, `ReactiveApiExceptionHandler`, `ReactiveCurrentActorProvider`, `Blocking` helper — imported only by WebFlux services). This split exists because `spring-webmvc` on the classpath would flip WebFlux apps to servlet mode. → ADR-017.
6. **Error semantics across HTTP**: provider internal endpoints throw the provider's existing exceptions; the provider's existing `@RestControllerAdvice` renders `ApiError` (with `code` and `retryAt`). Consumers catch `FeignException`, decode `ApiError`, and map `code` → consumer-side exception (mirroring the old in-process translation). → ADR-018.
7. **Config Server** serves per-service YAML from `classpath:/config-repo` (works identically local and in Docker; no volume-path juggling). docker-compose uses one postgres container + init SQL creating 7 DBs. → ADR-019.
8. **Swagger through Gateway**: per-service direct routes `/<service-name>/**` with `StripPrefix=1` + `server.forward-headers-strategy: framework` on services so springdoc honors `X-Forwarded-Prefix`. Verify in Task 17; fallback: document direct ports.

## Target Structure

```
lab2 (repo root)
├── build.gradle.kts / settings.gradle.kts / gradle/libs.versions.toml / .dockerignore
├── shared-lib/          (pure Java shared kernel)
├── shared-web/          (servlet web shared, MVC services only)
├── shared-webflux/      (reactive web shared, WebFlux services only)
├── eureka-server/  config-server/ (src/main/resources/config-repo/*.yml)  api-gateway/
├── identity-service/  media-service/  plants-service/  moderation-service/
├── geo-service/  tournaments-service/  feed-service/
│     └── each: src/main/java/com/plantarena/<ctx>/…, src/main/resources/{application.yml,db/migration/}
├── scripts/docker/init-databases.sql, scripts/local/create-databases.sql
├── docker-compose.yml
├── docs/ (existing + architecture.md, context-map.md update, api-contracts/, new ADRs)
├── PlantArena API/ (Bruno collection — unchanged)
└── monolith-reference/  (TEMPORARY — deleted in Task 17)
```

---

### Task 1: Branch `lab2` + monorepo scaffold

**Files:**
- Create: `settings.gradle.kts` (replace), `build.gradle.kts` (replace), `gradle/libs.versions.toml`, `.dockerignore`
- Move: `src/`, `build.gradle.kts`, `Dockerfile`, `docker-compose.yml`, `scripts/` → `monolith-reference/`

- [x] **Step 1: Create branch and move monolith aside**

```bash
git checkout main && git checkout -b lab2
mkdir monolith-reference
git mv src monolith-reference/src
git mv build.gradle.kts monolith-reference/build.gradle.kts
git mv Dockerfile monolith-reference/Dockerfile
git mv docker-compose.yml monolith-reference/docker-compose.yml
git mv scripts monolith-reference/scripts
rm settings.gradle.kts   # replaced below
```

- [x] **Step 2: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "plant-arena"

include(
    "shared-lib",
    "shared-web",
    "shared-webflux",
    "eureka-server",
    "config-server",
    "api-gateway",
    "identity-service",
    "media-service",
    "plants-service",
    "moderation-service",
    "geo-service",
    "tournaments-service",
    "feed-service",
)
```

- [x] **Step 3: Write root `build.gradle.kts`**

```kotlin
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.springframework.boot") version "4.0.8" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.plantarena"
    version = "0.2.0-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    plugins.withType<JavaPlugin>().configureEach {
        extensions.configure<JavaPluginExtension> {
            toolchain { languageVersion = JavaLanguageVersion.of(21) }
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = TestExceptionFormat.SHORT
        }
    }
}
```

- [x] **Step 4: Write `gradle/libs.versions.toml`**

```toml
[versions]
springBoot = "4.0.8"
springCloud = "2025.1.4"
springdoc = "3.1.1"
archunit = "1.5.0"
onnxruntime = "1.20.0"

[libraries]
spring-boot-webmvc = { module = "org.springframework.boot:spring-boot-starter-webmvc" }
spring-boot-webflux = { module = "org.springframework.boot:spring-boot-starter-webflux" }
spring-boot-jdbc = { module = "org.springframework.boot:spring-boot-starter-jdbc" }
spring-boot-jpa = { module = "org.springframework.boot:spring-boot-starter-data-jpa" }
spring-boot-r2dbc = { module = "org.springframework.boot:spring-boot-starter-data-r2dbc" }
spring-boot-validation = { module = "org.springframework.boot:spring-boot-starter-validation" }
spring-boot-actuator = { module = "org.springframework.boot:spring-boot-starter-actuator" }
spring-security-crypto = { module = "org.springframework.security:spring-security-crypto" }
eureka-server = { module = "org.springframework.cloud:spring-cloud-starter-netflix-eureka-server" }
eureka-client = { module = "org.springframework.cloud:spring-cloud-starter-netflix-eureka-client" }
config-client = { module = "org.springframework.cloud:spring-cloud-starter-config" }
config-server = { module = "org.springframework.cloud:spring-cloud-config-server" }
gateway-webflux = { module = "org.springframework.cloud:spring-cloud-starter-gateway-server-webflux" }
openfeign = { module = "org.springframework.cloud:spring-cloud-starter-openfeign" }
circuitbreaker = { module = "org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j" }
openapi-webmvc = { module = "org.springdoc:springdoc-openapi-starter-webmvc-ui", version.ref = "springdoc" }
openapi-webflux = { module = "org.springdoc:springdoc-openapi-starter-webflux-ui", version.ref = "springdoc" }
flyway-core = { module = "org.flywaydb:flyway-core" }
flyway-postgresql = { module = "org.flywaydb:flyway-database-postgresql" }
postgresql = { module = "org.postgresql:postgresql" }
r2dbc-postgresql = { module = "org.postgresql:r2dbc-postgresql" }
onnxruntime = { module = "com.microsoft.onnxruntime:onnxruntime", version.ref = "onnxruntime" }
archunit = { module = "com.tngtech.archunit:archunit-junit6", version.ref = "archunit" }
spring-boot-test = { module = "org.springframework.boot:spring-boot-starter-test" }
spring-boot-webmvc-test = { module = "org.springframework.boot:spring-boot-webmvc-test" }
spring-boot-webflux-test = { module = "org.springframework.boot:spring-boot-webflux-test" }
spring-boot-jdbc-test = { module = "org.springframework.boot:spring-boot-jdbc-test" }
testcontainers-postgresql = { module = "org.testcontainers:testcontainers-postgresql" }
awaitility = { module = "org.awaitility:awaitility" }
```

- [x] **Step 5: Write `.dockerignore`** (repo root — keeps Docker build contexts small)

```
.git
.gradle
.idea
build
*/build
docs
monolith-reference
storage
PlantArena API
.env
use-case-diagram.png
```

- [x] **Step 6: Verify and commit**

```bash
./gradlew projects   # expect: 13 subprojects listed, BUILD SUCCESSFUL
git add -A && git commit -m "build(lab2): каркас монорепо — 13 модулей, каталог версий, монолит в monolith-reference"
```

---

### Task 2: Shared modules (`shared-lib`, `shared-web`, `shared-webflux`)

**Files:**
- Create: `shared-lib/build.gradle.kts` + copied classes from `monolith-reference/src/main/java/com/plantarena/shared/`
- Create: `shared-web/build.gradle.kts` + `TraceIdFilter`, `ApiExceptionHandler`, `SharedWebConfig`
- Create: `shared-webflux/build.gradle.kts` + `TraceIdWebFilter`, `ReactiveApiExceptionHandler`, `SharedWebfluxConfig`, `ReactiveCurrentActorProvider`, `Blocking`

- [ ] **Step 1: `shared-lib` — pure Java shared kernel**

`shared-lib/build.gradle.kts`:
```kotlin
plugins { `java-library` }

dependencies {
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}
```

Copy (packages unchanged):
```bash
mkdir -p shared-lib/src/main/java/com/plantarena/shared
cp -r monolith-reference/src/main/java/com/plantarena/shared/event shared-lib/src/main/java/com/plantarena/shared/
cp -r monolith-reference/src/main/java/com/plantarena/shared/security shared-lib/src/main/java/com/plantarena/shared/
mkdir -p shared-lib/src/main/java/com/plantarena/shared/web
for f in ApiError.java PaginationParams.java InvalidPaginationException.java; do
  cp monolith-reference/src/main/java/com/plantarena/shared/web/$f shared-lib/src/main/java/com/plantarena/shared/web/
done
```
Copied files need **no changes** (they are pure Java; verify none import servlet/Spring — `ApiError`, `PaginationParams`, `InvalidPaginationException`, all of `event/` and `security/` are pure).

- [ ] **Step 2: `shared-web` — servlet web shared (MVC services only)**

`shared-web/build.gradle.kts`:
```kotlin
plugins { `java-library` }

dependencies {
    api(project(":shared-lib"))
    api("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}
```

Copy with package change `com.plantarena.shared.web` → **keep the same package** (it is already `com.plantarena.shared.web`):
```bash
mkdir -p shared-web/src/main/java/com/plantarena/shared/web
for f in TraceIdFilter.java ApiExceptionHandler.java SharedWebConfig.java; do
  cp monolith-reference/src/main/java/com/plantarena/shared/web/$f shared-web/src/main/java/com/plantarena/shared/web/
done
```
`SharedWebConfig.java` must register the advice as a bean (component scan will not cross modules). Replace its content with:
```java
package com.plantarena.shared.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SharedWebConfig {

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    public ApiExceptionHandler apiExceptionHandler() {
        return new ApiExceptionHandler();
    }
}
```
(`ApiExceptionHandler` and `TraceIdFilter` copied unchanged.)

- [ ] **Step 3: `shared-webflux` — reactive web shared (WebFlux services only)**

`shared-webflux/build.gradle.kts`:
```kotlin
plugins { `java-library` }

dependencies {
    api(project(":shared-lib"))
    api("org.springframework:spring-webflux")
    api("io.projectreactor:reactor-core")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}
```

`shared-webflux/src/main/java/com/plantarena/shared/webflux/TraceIdWebFilter.java`:
```java
package com.plantarena.shared.webflux;

import java.util.UUID;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

public final class TraceIdWebFilter implements WebFilter {

    public static final String TRACE_ID_ATTRIBUTE = "plantarena.traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = UUID.randomUUID().toString();
        exchange.getAttributes().put(TRACE_ID_ATTRIBUTE, traceId);
        exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);
        return chain.filter(exchange);
    }
}
```

`shared-webflux/src/main/java/com/plantarena/shared/webflux/ReactiveApiExceptionHandler.java` (reactive twin of `ApiExceptionHandler`; same codes):
```java
package com.plantarena.shared.webflux;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.NotIdentifiedException;
import com.plantarena.shared.web.ApiError;
import com.plantarena.shared.web.InvalidPaginationException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

@RestControllerAdvice
public class ReactiveApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> status(ResponseStatusException e, ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return respond(status, "RESOURCE_NOT_FOUND".equals(status.name()) ? "RESOURCE_NOT_FOUND" : "HTTP_ERROR",
            e.getReason(), exchange);
    }

    @ExceptionHandler(NotIdentifiedException.class)
    public ResponseEntity<ApiError> notIdentified(NotIdentifiedException e, ServerWebExchange exchange) {
        return respond(HttpStatus.UNAUTHORIZED, "NOT_IDENTIFIED", e.getMessage(), exchange);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> accessDenied(AccessDeniedException e, ServerWebExchange exchange) {
        return respond(HttpStatus.FORBIDDEN, "ACCESS_DENIED", e.getMessage(), exchange);
    }

    @ExceptionHandler(InvalidPaginationException.class)
    public ResponseEntity<ApiError> invalidPagination(InvalidPaginationException e, ServerWebExchange exchange) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", e.getMessage(), exchange);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e, ServerWebExchange exchange) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_BODY", "Некорректное тело запроса", exchange);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e, ServerWebExchange exchange) {
        List<ApiError.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
            .map(f -> new ApiError.FieldError(f.getField(), f.getDefaultMessage()))
            .toList();
        ApiError error = error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Некорректные поля запроса", exchange)
            .withFieldErrors(fieldErrors);
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> invalidParameter(MethodArgumentTypeMismatchException e, ServerWebExchange exchange) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
            "Некорректное значение параметра: " + e.getName(), exchange);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, ServerWebExchange exchange) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка сервера", exchange);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String detail,
                                             ServerWebExchange exchange) {
        return ResponseEntity.status(status).body(error(status, code, detail, exchange));
    }

    private ApiError error(HttpStatus status, String code, String detail, ServerWebExchange exchange) {
        String traceId = (String) exchange.getAttribute(TraceIdWebFilter.TRACE_ID_ATTRIBUTE);
        return new ApiError(URI.create("about:blank"), status.getReasonPhrase(), status.value(), detail,
            exchange.getRequest().getURI(), code, List.of(), traceId);
    }
}
```
NOTE: check `ApiError`'s actual component list/retryAt in `monolith-reference/src/main/java/com/plantarena/shared/web/ApiError.java` and match the constructor exactly (it may have a `retryAt` component and a `withFieldErrors` helper — use what exists).

`shared-webflux/src/main/java/com/plantarena/shared/webflux/SharedWebfluxConfig.java`:
```java
package com.plantarena.shared.webflux;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SharedWebfluxConfig {

    @Bean
    public TraceIdWebFilter traceIdWebFilter() {
        return new TraceIdWebFilter();
    }

    @Bean
    public ReactiveApiExceptionHandler reactiveApiExceptionHandler() {
        return new ReactiveApiExceptionHandler();
    }
}
```

`shared-webflux/src/main/java/com/plantarena/shared/webflux/ReactiveCurrentActorProvider.java`:
```java
package com.plantarena.shared.webflux;

import com.plantarena.shared.security.CurrentActor;
import reactor.core.publisher.Mono;

public interface ReactiveCurrentActorProvider {

    Mono<CurrentActor> currentActor();
}
```

`shared-webflux/src/main/java/com/plantarena/shared/webflux/Blocking.java`:
```java
package com.plantarena.shared.webflux;

import java.util.concurrent.Callable;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class Blocking {

    private Blocking() {}

    public static <T> Mono<T> call(Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }

    public static <T> Mono<T> call(Supplier<T> supplier) {
        return Mono.fromSupplier(supplier).subscribeOn(Schedulers.boundedElastic());
    }
}
```

- [ ] **Step 4: Verify and commit**

```bash
./gradlew :shared-lib:build :shared-web:build :shared-webflux:build
git add -A && git commit -m "build(lab2): shared-модули — ядро, servlet-web, webflux (ReactiveCurrentActorProvider, Blocking)"
```

---

### Task 3: Eureka Server

**Files:** Create `eureka-server/build.gradle.kts`, `eureka-server/src/main/java/com/plantarena/eureka/EurekaServerApplication.java`, `eureka-server/src/main/resources/application.yml`

- [ ] **Step 1: Build file**

```kotlin
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(libs.eureka.server)
    implementation(libs.spring.boot.actuator)
}
```

- [ ] **Step 2: Application + config**

`EurekaServerApplication.java`:
```java
package com.plantarena.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
```

`application.yml`:
```yaml
spring:
  application:
    name: eureka-server
server:
  port: 8761
eureka:
  client:
    register-with-eureka: false
    fetch-registry: false
  server:
    wait-time-in-ms-when-sync-empty: 0
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

- [ ] **Step 3: Verify and commit**

```bash
./gradlew :eureka-server:build
git add -A && git commit -m "feat(lab2): eureka-server discovery :8761"
```

---

### Task 4: Config Server + config-repo

**Files:** Create `config-server/build.gradle.kts`, `config-server/src/main/java/com/plantarena/config/ConfigServerApplication.java`, `config-server/src/main/resources/application.yml`, `config-server/src/main/resources/config-repo/{identity,media,plants,moderation,geo,tournaments,feed}-service.yml`

- [ ] **Step 1: Build file + application**

```kotlin
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(libs.config.server)
    implementation(libs.spring.boot.actuator)
}
```

`ConfigServerApplication.java`:
```java
package com.plantarena.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
```

`application.yml`:
```yaml
spring:
  application:
    name: config-server
  profiles:
    active: native
  cloud:
    config:
      server:
        native:
          search-locations: classpath:/config-repo
server:
  port: 8888
```

- [ ] **Step 2: config-repo files** (docker-oriented values; local defaults live in each service's own `application.yml`)

`config-repo/identity-service.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/identity
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: identity
plantarena:
  bootstrap-admin:
    email: ${BOOTSTRAP_ADMIN_EMAIL:}
    password: ${BOOTSTRAP_ADMIN_PASSWORD:}
    display-name: ${BOOTSTRAP_ADMIN_DISPLAY_NAME:Admin}
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,circuitbreakers
  endpoint:
    health:
      show-details: always
```

`config-repo/media-service.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/media
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: media
plantarena:
  media:
    storage:
      root: ${MEDIA_STORAGE_ROOT:/app/storage/media}
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,circuitbreakers
```

`config-repo/plants-service.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/plants
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: plants
feign:
  circuitbreaker:
    enabled: true
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
    instances:
      media-service:
        baseConfig: default
      identity-service:
        baseConfig: default
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,circuitbreakers
```

`config-repo/moderation-service.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/moderation
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: moderation
plantarena:
  moderation:
    model-path: ${MODERATION_MODEL_PATH:/app/models/mobilenetv2-1.0.onnx}
feign:
  circuitbreaker:
    enabled: true
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
    instances:
      plants-service:
        baseConfig: default
      media-service:
        baseConfig: default
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,circuitbreakers
```

`config-repo/geo-service.yml`:
```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://postgres:5432/geo
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  flyway:
    enabled: true
    url: jdbc:postgresql://postgres:5432/geo
    user: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
    locations: classpath:db/migration
    schemas: geo
plantarena:
  geo:
    geohash-precision: 4
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

`config-repo/tournaments-service.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/tournaments
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: tournaments
plantarena:
  global:
    epoch-duration: 24h
    final-window-duration: 6h
  guests:
    session-ttl: 24h
    session-creation-limit-per-minute: 10
    vote-limit-per-minute: 30
feign:
  circuitbreaker:
    enabled: true
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
    instances:
      identity-service:
        baseConfig: default
      plants-service:
        baseConfig: default
      geo-service:
        baseConfig: default
      feed-service:
        baseConfig: default
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,circuitbreakers
```

`config-repo/feed-service.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/feed
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: feed
plantarena:
  feed:
    cursor-secret: ${FEED_CURSOR_SECRET:dev-only-cursor-secret}
    cursor-ttl: 1h
feign:
  circuitbreaker:
    enabled: true
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
    instances:
      tournaments-service:
        baseConfig: default
      plants-service:
        baseConfig: default
      identity-service:
        baseConfig: default
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,circuitbreakers
```

- [ ] **Step 3: Verify and commit**

```bash
./gradlew :config-server:build
git add -A && git commit -m "feat(lab2): config-server :8888 (native, classpath config-repo) + конфиги 7 сервисов"
```

---

### Task 5: API Gateway

**Files:** Create `api-gateway/build.gradle.kts`, `api-gateway/src/main/java/com/plantarena/gateway/ApiGatewayApplication.java`, `api-gateway/src/main/resources/application.yml`

- [ ] **Step 1: Build file + application**

```kotlin
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(libs.gateway.webflux)
    implementation(libs.eureka.client)
    implementation(libs.circuitbreaker)
    implementation(libs.spring.boot.actuator)
}
```

`ApiGatewayApplication.java`:
```java
package com.plantarena.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```

- [ ] **Step 2: `application.yml`** (route order matters: tournaments first so `/api/v1/me/invitations/**` and `/api/v1/me/global-entry` are not shadowed by identity's `/api/v1/me/**`)

```yaml
server:
  port: 8080
spring:
  application:
    name: api-gateway
  codec:
    max-in-memory-size: 16MB
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: tournaments-service
              uri: lb://tournaments-service
              predicates:
                - Path=/api/v1/tournaments/**,/api/v1/global/**,/api/v1/guest-sessions/**,/api/v1/windows/**,/api/v1/me/invitations/**,/api/v1/me/global-entry,/api/v1/invitations/**,/api/v1/tags/**,/api/v1/internal/**
            - id: feed-service
              uri: lb://feed-service
              predicates:
                - Path=/api/v1/feed/**
            - id: plants-service
              uri: lb://plants-service
              predicates:
                - Path=/api/v1/plants/**
            - id: media-service
              uri: lb://media-service
              predicates:
                - Path=/api/v1/files/**
            - id: identity-service
              uri: lb://identity-service
              predicates:
                - Path=/api/v1/users/**,/api/v1/me/**
            - id: identity-service-direct
              uri: lb://identity-service
              predicates:
                - Path=/identity-service/**
              filters:
                - StripPrefix=1
            - id: media-service-direct
              uri: lb://media-service
              predicates:
                - Path=/media-service/**
              filters:
                - StripPrefix=1
            - id: plants-service-direct
              uri: lb://plants-service
              predicates:
                - Path=/plants-service/**
              filters:
                - StripPrefix=1
            - id: moderation-service-direct
              uri: lb://moderation-service
              predicates:
                - Path=/moderation-service/**
              filters:
                - StripPrefix=1
            - id: geo-service-direct
              uri: lb://geo-service
              predicates:
                - Path=/geo-service/**
              filters:
                - StripPrefix=1
            - id: tournaments-service-direct
              uri: lb://tournaments-service
              predicates:
                - Path=/tournaments-service/**
              filters:
                - StripPrefix=1
            - id: feed-service-direct
              uri: lb://feed-service
              predicates:
                - Path=/feed-service/**
              filters:
                - StripPrefix=1
          discovery:
            locator:
              enabled: true
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka}
  instance:
    prefer-ip-address: true
management:
  endpoints:
    web:
      exposure:
        include: health,info,gateway
```

- [ ] **Step 3: Verify and commit**

```bash
./gradlew :api-gateway:build
git add -A && git commit -m "feat(lab2): api-gateway :8080 — роуты сервисов + swagger-маршруты (StripPrefix)"
```

---

### Task 6: identity-service

**Files:**
- Create: `identity-service/build.gradle.kts`, `IdentityServiceApplication.java`, `application.yml`, `config/` wiring, `adapter/in/web/InternalDirectoryController.java`, `adapter/in/web/InternalExceptionHandler.java`, test support
- Copy: identity context (main + tests), migrations

- [ ] **Step 1: Copy the context**

```bash
mkdir -p identity-service/src/main/java/com/plantarena identity-service/src/main/resources/db/migration
cp -r monolith-reference/src/main/java/com/plantarena/identity identity-service/src/main/java/com/plantarena/
cp monolith-reference/src/main/resources/db/migration/identity/*.sql identity-service/src/main/resources/db/migration/
mkdir -p identity-service/src/test/java/com/plantarena
cp -r monolith-reference/src/test/java/com/plantarena/identity identity-service/src/test/java/com/plantarena/
```

- [ ] **Step 2: `build.gradle.kts`**

```kotlin
plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":shared-lib"))
    implementation(project(":shared-web"))
    implementation(libs.spring.boot.webmvc)
    implementation(libs.spring.boot.jdbc)
    implementation(libs.spring.boot.validation)
    implementation(libs.spring.boot.actuator)
    implementation(libs.spring.security.crypto)
    implementation(libs.eureka.client)
    implementation(libs.config.client)
    implementation(libs.openapi.webmvc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.boot.jdbc.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.archunit)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.springCloud.get()}")
    }
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*IT.class")
    shouldRunAfter(tasks.test)
}
tasks.test { exclude("**/*IT.class") }
tasks.named("check") { dependsOn(integrationTestTask) }
tasks.jar { enabled = false }
```

- [ ] **Step 3: Application class + config**

`identity-service/src/main/java/com/plantarena/identity/IdentityServiceApplication.java`:
```java
package com.plantarena.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.plantarena.identity", "com.plantarena.shared.web"})
public class IdentityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
```

`identity-service/src/main/java/com/plantarena/identity/config/IdentityServiceConfig.java` (replaces monolith `config/ClockConfig` + `config/IdentityWiringConfig` + `config/OpenApiConfig`):
```java
package com.plantarena.identity.config;

import com.plantarena.identity.adapter.in.web.DemoHeaderCurrentActorProvider;
import com.plantarena.identity.application.port.in.ResolveActorUseCase;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdentityServiceConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }

    @Bean
    public OpenAPI identityOpenApi() {
        return new OpenAPI().info(new Info()
            .title("Plant Arena Identity Service API")
            .version("1")
            .description("Пользователи, роли, профиль, разрешение актёра (демо-режим ADR-005)"));
    }
}
```
NOTE: the monolith `IdentityWiringConfig` gated `DemoHeaderCurrentActorProvider` to dev/test profiles and failed startup otherwise. In lab2 all deployments use demo auth (JWT is lab 3), so register the provider unconditionally — copy `DemoHeaderCurrentActorProvider` (already in `identity/adapter/in/web/`) and declare it as a `@Bean` in `IdentityServiceConfig`:
```java
    @Bean
    public DemoHeaderCurrentActorProvider currentActorProvider(ResolveActorUseCase resolveActorUseCase) {
        return new DemoHeaderCurrentActorProvider(resolveActorUseCase);
    }
```
Delete `identity/adapter/in/jobs/BootstrapAdminRunner`'s dependency on old config — it stays as-is (it reads `plantarena.bootstrap-admin.*` properties itself).

- [ ] **Step 4: `application.yml`**

```yaml
spring:
  application:
    name: identity-service
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/identity}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: identity
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB
server:
  port: 8081
  forward-headers-strategy: framework
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka}
  instance:
    prefer-ip-address: true
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,circuitbreakers
plantarena:
  bootstrap-admin:
    email: ${BOOTSTRAP_ADMIN_EMAIL:}
    password: ${BOOTSTRAP_ADMIN_PASSWORD:}
    display-name: ${BOOTSTRAP_ADMIN_DISPLAY_NAME:Admin}
```

- [ ] **Step 5: Internal endpoints (the service's published contract)**

`identity-service/src/main/java/com/plantarena/identity/adapter/in/web/InternalDirectoryController.java`:
```java
package com.plantarena.identity.adapter.in.web;

import com.plantarena.identity.api.UserDirectory;
import com.plantarena.identity.application.port.in.ResolveActorUseCase;
import com.plantarena.shared.security.CurrentActor;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalDirectoryController {

    private final ResolveActorUseCase resolveActor;
    private final UserDirectory userDirectory;

    @GetMapping("/actors/{userId}")
    public ActorResponse actor(@PathVariable UUID userId) {
        CurrentActor actor = resolveActor.resolve(userId);
        return new ActorResponse(actor.userId(),
            actor.roles().stream().map(Enum::name).toList());
    }

    @GetMapping("/users/{userId}/profile")
    public UserProfileResponse profile(@PathVariable UUID userId) {
        return userDirectory.findById(userId)
            .map(u -> new UserProfileResponse(u.id(), u.displayName(), u.active()))
            .orElseThrow(() -> new com.plantarena.identity.application.UserNotFoundException(userId));
    }

    @GetMapping("/users/{userId}/location")
    public UserLocationResponse location(@PathVariable UUID userId) {
        return userDirectory.findLocation(userId)
            .map(l -> new UserLocationResponse(l.latitude(), l.longitude(), l.locationVersion()))
            .orElseThrow(() -> new com.plantarena.identity.application.UserNotFoundException(userId));
    }

    public record ActorResponse(UUID userId, List<String> roles) {}
    public record UserProfileResponse(UUID id, String displayName, boolean active) {}
    public record UserLocationResponse(double latitude, double longitude, long locationVersion) {}
}
```

`identity-service/src/main/java/com/plantarena/identity/adapter/in/web/InternalExceptionHandler.java` (maps `NotIdentifiedException` → 404 for the actor endpoint; `UserNotFoundException` → 404 is already handled by `IdentityExceptionHandler`):
```java
package com.plantarena.identity.adapter.in.web;

import com.plantarena.shared.security.NotIdentifiedException;
import com.plantarena.shared.web.ApiError;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class InternalExceptionHandler {

    @ExceptionHandler(NotIdentifiedException.class)
    public ResponseEntity<ApiError> notIdentified(NotIdentifiedException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
            URI.create("about:blank"), "Not Found", 404,
            "Актёр не разрешён: " + e.getMessage(), URI.create("/internal/actors"),
            "ACTOR_NOT_FOUND", List.of(), UUID.randomUUID().toString()));
    }
}
```
NOTE: match `ApiError`'s real constructor (see the actual record in `monolith-reference`); if it has a `retryAt` component pass `null`.

- [ ] **Step 6: Tests**

Copy test support base (adjust from monolith `support/`):
```bash
mkdir -p identity-service/src/test/java/com/plantarena/support
cp monolith-reference/src/test/java/com/plantarena/support/PostgresSupport.java identity-service/src/test/java/com/plantarena/support/
```
Create `identity-service/src/test/java/com/plantarena/support/AbstractIntegrationTest.java` adapted from the monolith version (`monolith-reference/src/test/java/com/plantarena/support/AbstractIntegrationTest.java`): keep `@SpringBootTest(webEnvironment = MOCK) + @AutoConfigureMockMvc + @ActiveProfiles("test")` + `@DynamicPropertySource` wiring `spring.datasource.url` to the `PostgresSupport` container; **add** properties `eureka.client.enabled=false` and `spring.cloud.config.enabled=false`.

Create `identity-service/src/test/resources/application-test.yml`:
```yaml
spring:
  cloud:
    config:
      enabled: false
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: identity
eureka:
  client:
    enabled: false
plantarena:
  bootstrap-admin:
    email: admin@plantarena.local
    password: admin-password
    display-name: Admin
```

Test adjustments:
- Keep: all `identity/**` unit tests, fakes, `JdbcUserRepositoryContractIT`, `IdentityApiIT` (self-contained: only identity endpoints + bootstrap admin).
- `IdentityWiringConfigTest` — delete (wiring changed; the provider is now unconditional).
- Add `identity-service/src/test/java/com/plantarena/architecture/IdentityLayerRulesTest.java`: copy `monolith-reference/src/test/java/com/plantarena/architecture/LayerRulesTest.java`, keep rules 1, 2, 4, 5, 6 (layering, domain purity, JDBC only in `adapter.out.persistence`, controllers clean, shared independence) scoped to `com.plantarena.identity`; drop JPA-absence rule 3 for this service (still true here — keep it if it passes trivially).

- [ ] **Step 7: Verify and commit**

```bash
./gradlew :identity-service:test            # unit tests green
./gradlew :identity-service:integrationTest # Testcontainers ITs green (needs Docker)
./gradlew :identity-service:build
git add -A && git commit -m "feat(lab2): identity-service :8081 — контекст identity, внутренние эндпоинты /internal, Flyway, тесты"
```

---

### Task 7: media-service

**Files:** analogous to Task 6 + Feign identity client + remote actor provider + internal media endpoints.

- [ ] **Step 1: Copy the context**

```bash
mkdir -p media-service/src/main/java/com/plantarena media-service/src/main/resources/db/migration
cp -r monolith-reference/src/main/java/com/plantarena/media media-service/src/main/java/com/plantarena/
cp monolith-reference/src/main/resources/db/migration/media/*.sql media-service/src/main/resources/db/migration/
mkdir -p media-service/src/test/java/com/plantarena
cp -r monolith-reference/src/test/java/com/plantarena/media media-service/src/test/java/com/plantarena/
cp -r monolith-reference/src/test/java/com/plantarena/support media-service/src/test/java/com/plantarena/
```

- [ ] **Step 2: `build.gradle.kts`** — same as identity-service (Task 6 Step 2) with additions:
```kotlin
    implementation(libs.openfeign)
    implementation(libs.circuitbreaker)
```
and on the application class `@EnableFeignClients`. Full file = identity's file + the two lines above (keep `tasks.jar { enabled = false }`, IT task, BOM imports).

- [ ] **Step 3: Application class**

`media-service/src/main/java/com/plantarena/media/MediaServiceApplication.java`:
```java
package com.plantarena.media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.plantarena.media", "com.plantarena.shared.web"})
@EnableFeignClients(basePackages = "com.plantarena.media.api.client")
public class MediaServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediaServiceApplication.class, args);
    }
}
```

- [ ] **Step 4: Feign identity client + remote actor provider**

`media-service/src/main/java/com/plantarena/media/api/client/IdentityDirectoryClient.java`:
```java
package com.plantarena.media.api.client;

import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "identity-service")
public interface IdentityDirectoryClient {

    @GetMapping("/internal/actors/{userId}")
    ActorView getActor(@PathVariable("userId") UUID userId);

    record ActorView(UUID userId, List<String> roles) {}
}
```

`media-service/src/main/java/com/plantarena/media/adapter/in/web/RemoteHeaderCurrentActorProvider.java` (same semantics as identity's `DemoHeaderCurrentActorProvider`, but resolves via Feign):
```java
package com.plantarena.media.adapter.in.web;

import com.plantarena.media.api.client.IdentityDirectoryClient;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.shared.security.NotIdentifiedException;
import feign.FeignException;
import java.util.EnumSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
@RequiredArgsConstructor
public class RemoteHeaderCurrentActorProvider implements CurrentActorProvider {

    public static final String DEMO_USER_ID_HEADER = "X-Demo-User-Id";

    private final IdentityDirectoryClient identityClient;

    @Override
    public CurrentActor currentActor() {
        ServletRequestAttributes attributes =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new NotIdentifiedException("Нет текущего HTTP-запроса: идентификация невозможна");
        }
        String header = attributes.getRequest().getHeader(DEMO_USER_ID_HEADER);
        if (header == null || header.isBlank()) {
            return CurrentActor.guest();
        }
        UUID userId;
        try {
            userId = UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            throw new NotIdentifiedException("Некорректный " + DEMO_USER_ID_HEADER + ": " + header);
        }
        try {
            IdentityDirectoryClient.ActorView view = identityClient.getActor(userId);
            EnumSet<AppRole> roles = EnumSet.noneOf(AppRole.class);
            view.roles().forEach(role -> roles.add(AppRole.valueOf(role)));
            return CurrentActor.identified(view.userId(), roles);
        } catch (FeignException.NotFound e) {
            throw new NotIdentifiedException("Неизвестный или деактивированный пользователь: " + userId);
        }
    }
}
```

- [ ] **Step 5: Internal media endpoints**

`media-service/src/main/java/com/plantarena/media/adapter/in/web/InternalMediaController.java`:
```java
package com.plantarena.media.adapter.in.web;

import com.plantarena.media.api.MediaAssetClaims;
import com.plantarena.media.api.MediaAssets;
import com.plantarena.media.application.MediaAssetNotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/files")
@RequiredArgsConstructor
public class InternalMediaController {

    private final MediaAssets mediaAssets;
    private final MediaAssetClaims mediaAssetClaims;

    @GetMapping("/{assetId}/metadata")
    public MediaAssetMetadataResponse metadata(@PathVariable UUID assetId) {
        return mediaAssets.findById(assetId)
            .map(a -> new MediaAssetMetadataResponse(a.id(), a.ownerId(), a.fingerprint(), a.fingerprintVersion()))
            .orElseThrow(() -> new MediaAssetNotFoundException(assetId));
    }

    @GetMapping("/{assetId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID assetId) {
        return mediaAssets.loadContent(assetId)
            .map(c -> ResponseEntity.ok().contentType(MediaType.parseMediaType(c.mimeType()))
                .body(c.content()))
            .orElseThrow(() -> new MediaAssetNotFoundException(assetId));
    }

    @PostMapping("/{assetId}/claims")
    public void claim(@PathVariable UUID assetId, @RequestBody ClaimRequest request) {
        mediaAssetClaims.claim(assetId, request.plantId(), request.publiclyVisible());
    }

    @DeleteMapping("/{assetId}/claims/{plantId}")
    public void release(@PathVariable UUID assetId, @PathVariable UUID plantId) {
        mediaAssetClaims.release(assetId, plantId);
    }

    public record MediaAssetMetadataResponse(UUID id, UUID ownerId, String fingerprint, int fingerprintVersion) {}
    public record ClaimRequest(UUID plantId, boolean publiclyVisible) {}
}
```
(`MediaExceptionHandler` already maps `MediaAssetNotFoundException` → 404 and `AssetInUseException` → 409 — no changes.)

- [ ] **Step 6: Config + yml**

`media-service/src/main/java/com/plantarena/media/config/MediaServiceConfig.java`:
```java
package com.plantarena.media.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MediaServiceConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }

    @Bean
    public OpenAPI mediaOpenApi() {
        return new OpenAPI().info(new Info()
            .title("Plant Arena Media Service API").version("1")
            .description("Загрузка/скачивание/удаление изображений, клеймы ассетов"));
    }
}
```

`media-service/src/main/resources/application.yml` — copy identity's (Task 6 Step 4) and change: `name: media-service`, `port: 8082`, datasource db `media`, `flyway.schemas: media`, add:
```yaml
plantarena:
  media:
    storage:
      root: ${MEDIA_STORAGE_ROOT:./storage/media}
```

- [ ] **Step 7: Tests**

- `application-test.yml` (test resources) — same pattern as identity's, with `flyway.schemas: media` and `plantarena.media.storage.root: ${java.io.tmpdir}/media-test` (see monolith `AbstractIntegrationTest` for how it set temp storage — replicate).
- Adapt `AbstractIntegrationTest` (copied in Step 1) same as Task 6 Step 6.
- **Actor stub for tests**: `media-service/src/test/java/com/plantarena/media/support/TestHeaderCurrentActorProvider.java` — implements `CurrentActorProvider`, reads `X-Demo-User-Id` + `X-Demo-Roles` (comma-separated, default `USER`) headers, returns `CurrentActor.identified/guest` without network. Register it in test profile via `@TestConfiguration` imported by `AbstractIntegrationTest` (replaces the Feign-based provider in tests).
- Keep: all media unit tests + fakes, `JdbcMediaAssetRepositoryContractIT`, `JdbcAssetClaimRepositoryIT`, `MediaApiIT` — **adapt `MediaApiIT`**: replace DB-backed actor resolution with the stub (add `X-Demo-Roles` headers where the monolith relied on identity roles; e.g. admin actions send `X-Demo-Roles: ADMIN`).
- Add `architecture/MediaLayerRulesTest` (same adaptation as Task 6 Step 6).

- [ ] **Step 8: Verify and commit**

```bash
./gradlew :media-service:test :media-service:integrationTest :media-service:build
git add -A && git commit -m "feat(lab2): media-service :8082 — контекст media, Feign-идентификация, /internal/files, тесты"
```

---

### Task 8: plants-service

**Files:** copy plants context; replace `adapter/out/media` with Feign adapter; add identity client + actor provider; add internal endpoints (incl. new PENDING query); add event relay to tournaments.

- [ ] **Step 1: Copy the context**

```bash
mkdir -p plants-service/src/main/java/com/plantarena plants-service/src/main/resources/db/migration
cp -r monolith-reference/src/main/java/com/plantarena/plants plants-service/src/main/java/com/plantarena/
cp monolith-reference/src/main/resources/db/migration/plants/*.sql plants-service/src/main/resources/db/migration/
mkdir -p plants-service/src/test/java/com/plantarena
cp -r monolith-reference/src/test/java/com/plantarena/plants plants-service/src/test/java/com/plantarena/
cp -r monolith-reference/src/test/java/com/plantarena/support plants-service/src/test/java/com/plantarena/
rm -rf plants-service/src/main/java/com/plantarena/plants/adapter/out/media
```

- [ ] **Step 2: `build.gradle.kts`** — identity's file (Task 6 Step 2) + `implementation(libs.openfeign)` + `implementation(libs.circuitbreaker)`; `name`/dirs implied by module path.

- [ ] **Step 3: Application class** — same pattern as media (Task 7 Step 3): `PlantsServiceApplication`, `scanBasePackages = {"com.plantarena.plants", "com.plantarena.shared.web"}`, `@EnableFeignClients(basePackages = "com.plantarena.plants.api.client")`.

- [ ] **Step 4: Feign clients**

`plants-service/src/main/java/com/plantarena/plants/api/client/IdentityDirectoryClient.java` — exact copy of media's (Task 7 Step 4), package `com.plantarena.plants.api.client`.

`plants-service/src/main/java/com/plantarena/plants/api/client/MediaDirectoryClient.java`:
```java
package com.plantarena.plants.api.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "media-service")
public interface MediaDirectoryClient {

    @GetMapping("/internal/files/{assetId}/metadata")
    MediaAssetMetadataView getMetadata(@PathVariable("assetId") UUID assetId);

    @GetMapping("/internal/files/{assetId}/content")
    ResponseEntity<byte[]> getContent(@PathVariable("assetId") UUID assetId);

    @PostMapping("/internal/files/{assetId}/claims")
    void claim(@PathVariable("assetId") UUID assetId, @RequestBody ClaimRequest request);

    @DeleteMapping("/internal/files/{assetId}/claims/{plantId}")
    void release(@PathVariable("assetId") UUID assetId, @PathVariable("plantId") UUID plantId);

    record MediaAssetMetadataView(UUID id, UUID ownerId, String fingerprint, int fingerprintVersion) {}
    record ClaimRequest(UUID plantId, boolean publiclyVisible) {}
}
```

`plants-service/src/main/java/com/plantarena/plants/api/client/TournamentsEventsClient.java`:
```java
package com.plantarena.plants.api.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "tournaments-service")
public interface TournamentsEventsClient {

    @PostMapping("/internal/events/plant-moderation-decided")
    void plantModerationDecided(@RequestBody PlantModerationDecidedPayload payload);

    record PlantModerationDecidedPayload(UUID plantId, String decision) {}
}
```

- [ ] **Step 5: HTTP adapter replacing `InProcessMediaGateway`**

`plants-service/src/main/java/com/plantarena/plants/adapter/out/media/HttpMediaGateway.java` — implements BOTH ports (`MediaAssetsGateway`, `MediaAssetClaimsGateway`), mirroring the in-process translation (404 → `null`/`AssetNotFoundException` semantics, 409 `ASSET_IN_USE` → `AssetAlreadyClaimedException`):
```java
package com.plantarena.plants.adapter.out.media;

import com.plantarena.plants.api.client.MediaDirectoryClient;
import com.plantarena.plants.application.AssetAlreadyClaimedException;
import com.plantarena.plants.application.port.out.MediaAssetClaimsGateway;
import com.plantarena.plants.application.port.out.MediaAssetsGateway;
import feign.FeignException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpMediaGateway implements MediaAssetsGateway, MediaAssetClaimsGateway {

    private final MediaDirectoryClient mediaClient;

    @Override
    public AssetMetadata findById(UUID assetId) {
        try {
            MediaDirectoryClient.MediaAssetMetadataView view = mediaClient.getMetadata(assetId);
            return new AssetMetadata(view.id(), view.ownerId(), view.fingerprint(), view.fingerprintVersion());
        } catch (FeignException.NotFound e) {
            return null;
        }
    }

    @Override
    public void claim(UUID assetId, UUID plantId, boolean publiclyVisible) {
        try {
            mediaClient.claim(assetId, new MediaDirectoryClient.ClaimRequest(plantId, publiclyVisible));
        } catch (FeignException.Conflict e) {
            throw new AssetAlreadyClaimedException(assetId);
        }
    }

    @Override
    public void release(UUID assetId, UUID plantId) {
        mediaClient.release(assetId, plantId);
    }
}
```
NOTE: check the exact signatures/record names of `MediaAssetsGateway.AssetMetadata` and `AssetAlreadyClaimedException`'s constructor in the copied code and match them (the in-process adapter in `monolith-reference/src/main/java/com/plantarena/plants/adapter/out/media/InProcessMediaGateway.java` is the reference — replicate its translations exactly).

- [ ] **Step 6: Actor provider** — copy media's `RemoteHeaderCurrentActorProvider` (Task 7 Step 4) into `com.plantarena.plants.adapter.in.web`, importing `com.plantarena.plants.api.client.IdentityDirectoryClient`.

- [ ] **Step 7: New repository port method for moderation polling**

In `plants-service/.../plants/domain/PlantRepository.java` add:
```java
java.util.List<Plant> findByModerationStatus(ModerationStatus status, int limit);
```
Implement in `adapter/out/persistence/JdbcPlantRepository.java` (`SELECT ... WHERE moderation_status = :status ORDER BY created_at, id LIMIT :limit`, same row mapping as `findById`) and in the test fake `application/support/InMemoryPlantRepository.java` (filter + sort by createdAt, limit). Extend `PlantRepositoryContractTest` with a case: saved PENDING/APPROVED plants are returned filtered and limited.

- [ ] **Step 8: Internal endpoints**

`plants-service/src/main/java/com/plantarena/plants/adapter/in/web/InternalPlantController.java`:
```java
package com.plantarena.plants.adapter.in.web;

import com.plantarena.plants.api.PlantDirectory;
import com.plantarena.plants.api.PlantEligibility;
import com.plantarena.plants.api.PlantLifecycle;
import com.plantarena.plants.api.PlantModeration;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.event.PlantModerationDecidedEvent;
import com.plantarena.plants.application.port.out.PlantRepositoryPortLike; // NOT REAL — see note
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalPlantController {

    private final PlantDirectory plantDirectory;
    private final PlantModeration plantModeration;
    private final PlantLifecycle plantLifecycle;
    private final PlantEligibility plantEligibility;
    private final com.plantarena.plants.application.port.out.ModerationQueueQuery moderationQueue; // new port, see below

    @GetMapping("/plants/{plantId}")
    public PlantDataResponse get(@PathVariable UUID plantId) {
        return plantDirectory.findById(plantId)
            .map(p -> new PlantDataResponse(p.id(), p.ownerId(), p.assetId(), p.title(),
                p.moderationStatus().name(), p.lifeStatus().name()))
            .orElseThrow(() -> new PlantNotFoundException(plantId));
    }

    @GetMapping("/plants")
    public List<PendingPlantResponse> pending(
            @RequestParam("moderationStatus") String moderationStatus,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return moderationQueue.findPending(limit).stream()
            .map(p -> new PendingPlantResponse(p.getId(), p.getAssetId()))
            .toList();
    }

    @PostMapping("/plants/{plantId}/moderation-decision")
    public PlantDataResponse decide(@PathVariable UUID plantId, @RequestBody DecisionRequest request) {
        var data = plantModeration.recordDecision(plantId,
            PlantModeration.Decision.valueOf(request.decision()), request.reason());
        return new PlantDataResponse(data.id(), data.ownerId(), data.assetId(), data.title(),
            data.moderationStatus().name(), data.lifeStatus().name());
    }

    @PostMapping("/plants/{plantId}/death")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void die(@PathVariable UUID plantId, @RequestBody DeathRequest request) {
        plantLifecycle.registerDeath(plantId,
            PlantLifecycle.RestrictionKind.valueOf(request.kind()),
            request.cooldownExpiresAt(), request.reason(), request.sourceEntryId());
    }

    @PostMapping("/plants/reservations")
    public ReservationResponse reserve(@RequestBody ReserveRequest request) {
        UUID reservationId = plantEligibility.reserveSubmission(
            request.ownerId(), request.plantId(), request.idempotencyKey());
        return new ReservationResponse(reservationId);
    }

    @PostMapping("/plants/reservations/{reservationId}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@PathVariable UUID reservationId, @RequestBody ConfirmRequest request) {
        plantEligibility.confirmEligibility(request.ownerId(), request.plantId(), reservationId);
    }

    @PostMapping("/plants/reservations/{reservationId}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID reservationId) {
        plantEligibility.releaseReservation(reservationId);
    }

    public record PlantDataResponse(UUID id, UUID ownerId, UUID assetId, String title,
                                    String moderationStatus, String lifeStatus) {}
    public record PendingPlantResponse(UUID plantId, UUID assetId) {}
    public record DecisionRequest(String decision, String reason) {}
    public record DeathRequest(String kind, Instant cooldownExpiresAt, String reason, UUID sourceEntryId) {}
    public record ReserveRequest(UUID ownerId, UUID plantId, UUID idempotencyKey) {}
    public record ReservationResponse(UUID reservationId) {}
    public record ConfirmRequest(UUID ownerId, UUID plantId) {}
}
```
NOTE (fix before writing): remove the two placeholder imports at the top (`PlantRepositoryPortLike`, the inline `ModerationQueueQuery` reference is the real design): create a small application port `com.plantarena.plants.application.port.in.QueryPendingPlantsUseCase` (or `port/out` query port) with `List<PendingPlant> findPending(int limit)` implemented by a tiny application service that calls `PlantRepository.findByModerationStatus(PENDING, limit)` and maps to `record PendingPlant(UUID plantId, UUID assetId)`. Use it in the controller. `PlantExceptionHandler` already maps plants exceptions (404/409 with `retryAt`) — no changes needed.

- [ ] **Step 9: Event relay to tournaments**

`plants-service/src/main/java/com/plantarena/plants/adapter/out/events/TournamentsEventRelay.java`:
```java
package com.plantarena.plants.adapter.out.events;

import com.plantarena.plants.api.client.TournamentsEventsClient;
import com.plantarena.plants.api.event.PlantDiedEvent;
import com.plantarena.plants.api.event.PlantModerationDecidedEvent;
import com.plantarena.plants.api.event.PlantSubmittedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class TournamentsEventRelay {

    private final TournamentsEventsClient tournamentsEventsClient;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onModerationDecided(PlantModerationDecidedEvent event) {
        try {
            tournamentsEventsClient.plantModerationDecided(
                new TournamentsEventsClient.PlantModerationDecidedPayload(
                    event.payload().plantId(), event.payload().decision()));
        } catch (Exception e) {
            log.warn("Не доставлено событие PlantModerationDecided={} в tournaments-service: {}",
                event.payload().plantId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(PlantSubmittedEvent event) {
        log.debug("PlantSubmitted={} получен moderation-сервисом через polling", event.payload().plantId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDied(PlantDiedEvent event) {
        log.debug("PlantDied={} получателей нет (lab2)", event.payload().plantId());
    }
}
```
Also copy `EventWiringConfig` semantics: `plants-service/.../plants/config/PlantsServiceConfig.java` must declare the `IntegrationEventPublisher` bean bridging to Spring's `ApplicationEventPublisher` (copy from `monolith-reference/src/main/java/com/plantarena/config/EventWiringConfig.java`, package `com.plantarena.plants.config`), plus `Clock` and OpenAPI beans (same pattern as Task 7 Step 6).

- [ ] **Step 10: `application.yml`** — identity's template with: `name: plants-service`, `port: 8083`, db `plants`, `flyway.schemas: plants`. No `plantarena.*` settings (plants has none).

- [ ] **Step 11: Tests**

- `application-test.yml` + adapted `AbstractIntegrationTest` + `TestHeaderCurrentActorProvider` (copy from media-service, adjust packages).
- Keep: all plants domain/application unit tests + fakes; repository contract ITs (3 JDBC ones); `PlantExceptionHandlerTest`.
- **Adapt unit tests that used media api fakes**: `FakeMediaAssets`/`FakeMediaAssetClaims` implement the ports — unchanged ✓ (ports unchanged). Delete `PlantsApiIT` (cross-context: uploads via media).
- Extend `PlantRepositoryContractTest` per Step 7.
- Add `architecture/PlantsLayerRulesTest` (pattern from Task 6).

- [ ] **Step 12: Verify and commit**

```bash
./gradlew :plants-service:test :plants-service:integrationTest :plants-service:build
git add -A && git commit -m "feat(lab2): plants-service :8083 — Feign к media/identity/tournaments, /internal/plants, relay событий"
```

---

### Task 9: moderation-service

- [ ] **Step 1: Copy the context**

```bash
mkdir -p moderation-service/src/main/java/com/plantarena moderation-service/src/main/resources/db/migration
cp -r monolith-reference/src/main/java/com/plantarena/moderation moderation-service/src/main/java/com/plantarena/
cp monolith-reference/src/main/resources/db/migration/moderation/*.sql moderation-service/src/main/resources/db/migration/
mkdir -p moderation-service/src/test/java/com/plantarena
cp -r monolith-reference/src/test/java/com/plantarena/moderation moderation-service/src/test/java/com/plantarena/
cp -r monolith-reference/src/test/java/com/plantarena/support moderation-service/src/test/java/com/plantarena/
rm -rf moderation-service/src/main/java/com/plantarena/moderation/adapter/in/events
rm -rf moderation-service/src/main/java/com/plantarena/moderation/adapter/out/media
rm -rf moderation-service/src/main/java/com/plantarena/moderation/adapter/out/plants
```

- [ ] **Step 2: `build.gradle.kts`** — identity's template + `libs.openfeign`, `libs.circuitbreaker`, `libs.onnxruntime`, plus the model-download task (copy verbatim from `monolith-reference/build.gradle.kts` lines for `downloadModerationModel`, adjusting the model path to `layout.buildDirectory.file("models/mobilenetv2-1.0.onnx")`) and the `-Pinference` tag logic on `integrationTest` (copy from monolith).

- [ ] **Step 3: Application class** — `ModerationServiceApplication`, `scanBasePackages = {"com.plantarena.moderation", "com.plantarena.shared.web"}` (moderation has no user-facing auth — no actor provider needed), `@EnableFeignClients(basePackages = "com.plantarena.moderation.api.client")`, `@EnableScheduling` (was on monolith `ModerationWiringConfig` — put it here or on the service config).

- [ ] **Step 4: Feign clients**

`moderation-service/src/main/java/com/plantarena/moderation/api/client/PlantsDirectoryClient.java`:
```java
package com.plantarena.moderation.api.client;

import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "plants-service")
public interface PlantsDirectoryClient {

    @GetMapping("/internal/plants")
    List<PendingPlantView> getPlantsByModerationStatus(
        @RequestParam("moderationStatus") String moderationStatus,
        @RequestParam("limit") int limit);

    @PostMapping("/internal/plants/{plantId}/moderation-decision")
    void recordDecision(@PathVariable("plantId") UUID plantId, @RequestBody DecisionRequest request);

    record PendingPlantView(UUID plantId, UUID assetId) {}
    record DecisionRequest(String decision, String reason) {}
}
```

`moderation-service/src/main/java/com/plantarena/moderation/api/client/MediaContentClient.java`:
```java
package com.plantarena.moderation.api.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "media-service")
public interface MediaContentClient {

    @GetMapping("/internal/files/{assetId}/content")
    MediaContentView getContent(@PathVariable("assetId") UUID assetId);

    record MediaContentView(byte[] content, String mimeType) {}
}
```
NOTE: media returns raw bytes with Content-Type header, not JSON. Configure this client with a Feign `Decoder` that builds `MediaContentView` from `ResponseEntity<byte[]>` — simplest: declare the method as `org.springframework.http.ResponseEntity<byte[]> getContent(...)` and map in the adapter. Use that form.

- [ ] **Step 5: HTTP adapters replacing in-process ones**

`moderation-service/src/main/java/com/plantarena/moderation/adapter/out/plants/HttpPlantModerationGateway.java` (reference: `monolith-reference/.../moderation/adapter/out/plants/InProcessPlantModerationGateway.java`):
```java
package com.plantarena.moderation.adapter.out.plants;

import com.plantarena.moderation.api.client.PlantsDirectoryClient;
import com.plantarena.moderation.application.DecisionConflictException;
import com.plantarena.moderation.application.port.out.PlantModerationGateway;
import feign.FeignException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpPlantModerationGateway implements PlantModerationGateway {

    private final PlantsDirectoryClient plantsClient;

    @Override
    public void recordDecision(UUID plantId, Decision decision, String reason) {
        try {
            plantsClient.recordDecision(plantId,
                new PlantsDirectoryClient.DecisionRequest(decision.name(), reason));
        } catch (FeignException.Conflict e) {
            throw new DecisionConflictException(plantId);
        }
    }
}
```
(Match the real `PlantModerationGateway` signature and `DecisionConflictException` constructor from the copied code.)

`moderation-service/src/main/java/com/plantarena/moderation/adapter/out/media/HttpMediaContentGateway.java` — implements `MediaContentGateway`; calls `mediaContentClient.getContent(assetId)`; `FeignException.NotFound` → mirror the in-process behavior (check `InProcessMediaContentGateway`: `Optional`/null semantics) — return `null`/empty exactly as before.

- [ ] **Step 6: Pending-plants poller (replaces `PlantSubmittedHandler`)**

`moderation-service/src/main/java/com/plantarena/moderation/adapter/in/jobs/ModerationPendingPlantPoller.java`:
```java
package com.plantarena.moderation.adapter.in.jobs;

import com.plantarena.moderation.api.client.PlantsDirectoryClient;
import com.plantarena.moderation.application.port.in.CreateModerationJobUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ModerationPendingPlantPoller {

    private final PlantsDirectoryClient plantsClient;
    private final CreateModerationJobUseCase createModerationJob;

    @Scheduled(fixedDelay = 5000)
    public void poll() {
        try {
            for (var plant : plantsClient.getPlantsByModerationStatus("PENDING", 50)) {
                createModerationJob.onPlantSubmitted(plant.plantId(), plant.assetId());
            }
        } catch (Exception e) {
            log.warn("Опрос PENDING-растений не удался: {}", e.getMessage());
        }
    }
}
```
(`CreateModerationJobService` is idempotent via `findLatestByPlantId` — safe redelivery.)

- [ ] **Step 7: Config + yml**

`moderation-service/.../moderation/config/ModerationServiceConfig.java`: `@Configuration @EnableScheduling`, beans: `Clock utcClock()`, `PlantClassifier plantClassifier(@Value("${plantarena.moderation.model-path}") String modelPath)` returning `new OnnxPlantClassifier(Path.of(modelPath))` (copy from monolith `ModerationWiringConfig`), OpenAPI bean (title "Plant Arena Moderation Service API").

`application.yml`: identity's template with `name: moderation-service`, `port: 8084`, db `moderation`, `flyway.schemas: moderation`, plus:
```yaml
plantarena:
  moderation:
    model-path: ${MODERATION_MODEL_PATH:build/models/mobilenetv2-1.0.onnx}
```

- [ ] **Step 8: Tests**

- `application-test.yml` (`flyway.schemas: moderation`, eureka/config off) + adapted `AbstractIntegrationTest` (no actor stub needed — no auth).
- Keep: domain tests, application tests + fakes, `JdbcModerationJobRepositoryContractIT`, `OnnxPlantClassifierIT` (inference-tagged), `DeterministicPlantClassifier` support.
- Delete `ModerationApiIT` (cross-context: upload → submit → decide).
- Add `architecture/ModerationLayerRulesTest`.

- [ ] **Step 9: Verify and commit**

```bash
./gradlew :moderation-service:test :moderation-service:integrationTest :moderation-service:build
git add -A && git commit -m "feat(lab2): moderation-service :8084 — Feign к plants/media, poller PENDING-растений, ONNX"
```

---

### Task 10: geo-service (Reactor + R2DBC) ⭐

- [ ] **Step 1: Copy the context**

```bash
mkdir -p geo-service/src/main/java/com/plantarena geo-service/src/main/resources/db/migration
cp -r monolith-reference/src/main/java/com/plantarena/geo geo-service/src/main/java/com/plantarena/
cp monolith-reference/src/main/resources/db/migration/geo/*.sql geo-service/src/main/resources/db/migration/
mkdir -p geo-service/src/test/java/com/plantarena
cp -r monolith-reference/src/test/java/com/plantarena/geo geo-service/src/test/java/com/plantarena/
rm -rf geo-service/src/main/java/com/plantarena/geo/adapter/out/persistence
```
Keep `geo/api/ClusterAssignment.java` (HTTP contract reference) and `geo/domain/*` (Geohash, policies, ClusterSnapshot, ClusterMember — unchanged).

- [ ] **Step 2: `build.gradle.kts`**

```kotlin
plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":shared-lib"))
    implementation(project(":shared-webflux"))
    implementation(libs.spring.boot.webflux)
    implementation(libs.spring.boot.r2dbc)
    implementation(libs.spring.boot.actuator)
    implementation(libs.eureka.client)
    implementation(libs.config.client)
    implementation(libs.openapi.webflux)
    implementation(libs.r2dbc.postgresql)
    // Flyway работает по JDBC-каналу параллельно с R2DBC
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.webflux.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.archunit)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.springCloud.get()}")
    }
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*IT.class")
    shouldRunAfter(tasks.test)
}
tasks.test { exclude("**/*IT.class") }
tasks.named("check") { dependsOn(integrationTestTask) }
tasks.jar { enabled = false }
```
NOTE: `spring-boot-starter-data-r2dbc` + `r2dbc-postgresql` — NO `spring-boot-starter-jdbc`, NO `spring-boot-starter-data-jpa` (acceptance criterion 6).

- [ ] **Step 3: Application class**

`geo-service/src/main/java/com/plantarena/geo/GeoServiceApplication.java`:
```java
package com.plantarena.geo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(
    scanBasePackages = {"com.plantarena.geo", "com.plantarena.shared.webflux"},
    exclude = {DataSourceAutoConfiguration.class}
)
public class GeoServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(GeoServiceApplication.class, args);
    }
}
```
(`DataSourceAutoConfiguration` excluded so the JDBC driver on the classpath (for Flyway) does not create a competing pooled `DataSource`; Flyway builds its own from `spring.flyway.url`. If Flyway autoconfig requires a `DataSource` bean and fails, instead keep autoconfig on and set `spring.flyway.url` — verify at build; the exclude approach is the standard one.)

- [ ] **Step 4: Reactive repository port + R2DBC persistence**

Replace `geo/domain/ClusterSnapshotRepository.java` (blocking) with a reactive port:
```java
package com.plantarena.geo.domain;

import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ClusterSnapshotRepository {

    Mono<ClusterSnapshot> save(ClusterSnapshot snapshot);

    Flux<ClusterSnapshot> findByEpochId(UUID epochId);

    Mono<ClusterSnapshot> findById(UUID id);
}
```

`geo-service/src/main/java/com/plantarena/geo/adapter/out/persistence/ClusterSnapshotRow.java`:
```java
package com.plantarena.geo.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("geo.cluster_snapshot")
public record ClusterSnapshotRow(
    @Id UUID id,
    UUID epochId,
    String clusterKey,
    String policyVersion,
    Instant createdAt) {}
```

`geo-service/src/main/java/com/plantarena/geo/adapter/out/persistence/R2dbcClusterSnapshotRepository.java` (implements the domain port; members via `DatabaseClient` because `cluster_member` has a composite PK unsupported by Spring Data R2DBC):
```java
package com.plantarena.geo.adapter.out.persistence;

import com.plantarena.geo.domain.ClusterMember;
import com.plantarena.geo.domain.ClusterSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@RequiredArgsConstructor
public class R2dbcClusterSnapshotRepository implements com.plantarena.geo.domain.ClusterSnapshotRepository {

    private final ClusterSnapshotRowRepository snapshotRows;
    private final DatabaseClient databaseClient;

    @Override
    public Mono<ClusterSnapshot> save(ClusterSnapshot snapshot) {
        ClusterSnapshotRow row = new ClusterSnapshotRow(snapshot.id(), snapshot.epochId(),
            snapshot.clusterKey(), snapshot.policyVersion(), snapshot.createdAt());
        return snapshotRows.save(row)
            .then(deleteMembers(snapshot.id()))
            .then(insertMembers(snapshot))
            .thenReturn(snapshot);
    }

    @Override
    public Flux<ClusterSnapshot> findByEpochId(UUID epochId) {
        return snapshotRows.findByEpochIdOrderByClusterKeyAsc(epochId)
            .flatMap(this::loadWithMembers);
    }

    @Override
    public Mono<ClusterSnapshot> findById(UUID id) {
        return snapshotRows.findById(id).flatMap(this::loadWithMembers);
    }

    private Mono<Void> deleteMembers(UUID snapshotId) {
        return databaseClient.sql("DELETE FROM geo.cluster_member WHERE snapshot_id = :snapshotId")
            .bind("snapshotId", snapshotId)
            .then();
    }

    private Mono<Void> insertMembers(ClusterSnapshot snapshot) {
        return Flux.fromIterable(snapshot.members())
            .flatMap(m -> databaseClient.sql("""
                    INSERT INTO geo.cluster_member (snapshot_id, user_id, entry_id, location_version)
                    VALUES (:snapshotId, :userId, :entryId, :locationVersion)
                    """)
                .bind("snapshotId", snapshot.id())
                .bind("userId", m.userId())
                .bind("entryId", m.entryId())
                .bind("locationVersion", m.locationVersion())
                .then())
            .then();
    }

    private Mono<ClusterSnapshot> loadWithMembers(ClusterSnapshotRow row) {
        return databaseClient.sql("""
                SELECT user_id, entry_id, location_version
                FROM geo.cluster_member WHERE snapshot_id = :snapshotId
                """)
            .bind("snapshotId", row.id())
            .map((r, md) -> new ClusterSnapshot.SnapshotMember(
                r.get("user_id", UUID.class), r.get("entry_id", UUID.class),
                r.get("location_version", Long.class)))
            .all()
            .collectList()
            .map(members -> new ClusterSnapshot(row.id(), row.epochId(), row.clusterKey(),
                row.policyVersion(), members, row.createdAt()));
    }
}
```
NOTE: match the real `ClusterSnapshot`/`SnapshotMember` constructors from the copied domain code (they have a `fix(...)` freeze method — check how the JDBC adapter built instances and replicate; `ClusterSnapshot` may be immutable with a factory — use whatever exists).

`ClusterSnapshotRowRepository` (Spring Data):
```java
package com.plantarena.geo.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

interface ClusterSnapshotRowRepository extends ReactiveCrudRepository<ClusterSnapshotRow, UUID> {
    Flux<ClusterSnapshotRow> findByEpochIdOrderByClusterKeyAsc(UUID epochId);
}
```

- [ ] **Step 5: Reactive application service**

Replace `geo/application/ClusterAssignmentFacade.java` with `geo-service/.../geo/application/ClusterAssignmentService.java`:
```java
package com.plantarena.geo.application;

import com.plantarena.geo.api.ClusterAssignment;
import com.plantarena.geo.domain.ClusterMember;
import com.plantarena.geo.domain.ClusterSnapshot;
import com.plantarena.geo.domain.ClusterSnapshotRepository;
import com.plantarena.geo.domain.ClusteringPolicy;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class ClusterAssignmentService {

    private final ClusterSnapshotRepository repository;
    private final ClusteringPolicy clusteringPolicy;
    private final Clock clock;

    public Mono<List<ClusterAssignment.AssignedCluster>> assignClusters(UUID epochId,
                                                                        List<ClusterAssignment.Member> members) {
        List<ClusterMember> domainMembers = members.stream()
            .map(m -> new ClusterMember(m.entryId(), m.userId(), m.latitude(), m.longitude(),
                m.locationVersion()))
            .toList();
        return Mono.fromCallable(() -> clusteringPolicy.cluster(domainMembers))
            .flatMap(clusters -> Flux.fromIterable(clusters.entrySet())
                .map(e -> toSnapshot(epochId, e.getKey(), e.getValue()))
                .flatMap(repository::save)
                .then(Mono.fromSupplier(() -> clusters.entrySet().stream()
                    .map(e -> new ClusterAssignment.AssignedCluster(
                        findSnapshotId(epochId, e.getKey(), e.getValue()), e.getKey(),
                        e.getValue().stream().map(ClusterMember::entryId).toList()))
                    .toList())))
            .map(list -> list);
    }

    public Flux<ClusterSnapshot> findByEpoch(UUID epochId) {
        return repository.findByEpochId(epochId);
    }

    private ClusterSnapshot toSnapshot(UUID epochId, String clusterKey, List<ClusterMember> members) {
        // строит снапшот с новым UUID и фиксацией состава (см. домен: fix/фабрика)
        return new ClusterSnapshot(UUID.randomUUID(), epochId, clusterKey,
            clusteringPolicy.policyVersion(),
            members.stream().map(m -> new ClusterSnapshot.SnapshotMember(
                m.userId(), m.entryId(), m.locationVersion())).toList(),
            clock.instant());
    }

    private UUID findSnapshotId(UUID epochId, String clusterKey, List<ClusterMember> members) {
        return toSnapshotIdPlaceholder; // см. NOTE
    }
}
```
NOTE (fix before writing): the double-construction above is clumsy — restructure: build the `ClusterSnapshot` list FIRST (each with its UUID), then `Flux.fromIterable(snapshots).flatMap(repository::save).then(...)` returning `snapshots.map(s -> new AssignedCluster(s.id(), s.clusterKey(), entryIds))`. Write it that way — single construction, no placeholder method. Match the real `ClusterSnapshot` factory (`fix(...)`) and `ClusterAssignment` record signatures from the copied code.

- [ ] **Step 6: WebFlux controller**

`geo-service/src/main/java/com/plantarena/geo/adapter/in/web/InternalClusterController.java`:
```java
package com.plantarena.geo.adapter.in.web;

import com.plantarena.geo.api.ClusterAssignment;
import com.plantarena.geo.application.ClusterAssignmentService;
import com.plantarena.geo.domain.ClusterSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal/clusters")
@RequiredArgsConstructor
public class InternalClusterController {

    private final ClusterAssignmentService service;

    @PostMapping("/assignments")
    public Mono<List<ClusterAssignment.AssignedCluster>> assign(@RequestBody AssignmentRequest request) {
        return service.assignClusters(request.epochId(), request.members());
    }

    @GetMapping
    public Flux<ClusterSnapshotResponse> list(@RequestParam UUID epochId) {
        return service.findByEpoch(epochId)
            .map(s -> new ClusterSnapshotResponse(s.id(), s.epochId(), s.clusterKey(),
                s.policyVersion(), s.createdAt(), s.members().size()));
    }

    public record AssignmentRequest(UUID epochId, List<ClusterAssignment.Member> members) {}
    public record ClusterSnapshotResponse(UUID id, UUID epochId, String clusterKey,
                                          String policyVersion, Instant createdAt, int memberCount) {}
}
```
NOTE: `ClusterAssignment.AssignedCluster` must be JSON-serializable (record ✓). If `AssignedCluster`'s real shape differs (e.g. `snapshotId` name), keep the domain record — Feign consumers (tournaments) define a matching view.

- [ ] **Step 7: Config + yml**

`geo-service/.../geo/config/GeoServiceConfig.java`: `@Configuration @EnableConfigurationProperties(GeoClusteringSettings.class)`, beans `Clock utcClock()`, `ClusteringPolicy geohashClusteringPolicy(GeoClusteringSettings s)` (copy from monolith `GeoWiringConfig`), OpenAPI bean (webflux title).

`application.yml`:
```yaml
spring:
  application:
    name: geo-service
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}
  r2dbc:
    url: ${R2DBC_URL:r2dbc:postgresql://localhost:5432/geo}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  flyway:
    enabled: true
    url: ${FLYWAY_URL:jdbc:postgresql://localhost:5432/geo}
    user: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
    locations: classpath:db/migration
    schemas: geo
server:
  port: 8085
  forward-headers-strategy: framework
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka}
  instance:
    prefer-ip-address: true
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
plantarena:
  geo:
    geohash-precision: 4
```

- [ ] **Step 8: Tests**

- Keep unchanged: `GeohashTest`, `GeohashClusteringPolicyTest`, `ClusterSnapshotTest` (pure domain).
- Rewrite `ClusterAssignmentFacadeTest` → `ClusterAssignmentServiceTest` using `StepVerifier` with a reactive in-memory fake `ClusterSnapshotRepository` (implement the reactive port over a `ConcurrentHashMap`).
- Replace `JdbcClusterSnapshotRepositoryContractIT` with `R2dbcClusterSnapshotRepositoryIT`: Testcontainers PostgreSQL (reuse `PostgresSupport` pattern), `@DynamicPropertySource` sets BOTH `spring.r2dbc.url` (`r2dbc:postgresql://<host>:<port>/geo`) and `spring.flyway.url`, run Flyway, then exercise save/findByEpochId/findById. Drop the old in-memory contract test pair or port the fake per above.
- `application-test.yml`: eureka/config off, `plantarena.geo.geohash-precision: 4`.
- Add `architecture/GeoLayerRulesTest` — adapt: domain must not depend on Spring/infrastructure (allow `reactor.core` in domain — the reactive port requires it; document in the test's rule exceptions).

- [ ] **Step 9: Verify and commit**

```bash
./gradlew :geo-service:test :geo-service:integrationTest :geo-service:build
git add -A && git commit -m "feat(lab2): geo-service :8085 — полностью реактивный (WebFlux + R2DBC), Flyway по JDBC"
```

---

### Task 11: tournaments-service, часть 1 — ядро + JPA (Tournament, Tag)

- [ ] **Step 1: Copy the context**

```bash
mkdir -p tournaments-service/src/main/java/com/plantarena tournaments-service/src/main/resources/db/migration
cp -r monolith-reference/src/main/java/com/plantarena/tournaments tournaments-service/src/main/java/com/plantarena/
cp monolith-reference/src/main/resources/db/migration/tournaments/*.sql tournaments-service/src/main/resources/db/migration/
mkdir -p tournaments-service/src/test/java/com/plantarena
cp -r monolith-reference/src/test/java/com/plantarena/tournaments tournaments-service/src/test/java/com/plantarena/
cp -r monolith-reference/src/test/java/com/plantarena/support tournaments-service/src/test/java/com/plantarena/
rm -rf tournaments-service/src/main/java/com/plantarena/tournaments/adapter/out/geo
rm -rf tournaments-service/src/main/java/com/plantarena/tournaments/adapter/out/identity
rm -rf tournaments-service/src/main/java/com/plantarena/tournaments/adapter/out/plants
rm -rf tournaments-service/src/main/java/com/plantarena/tournaments/adapter/in/events
rm tournaments-service/src/main/java/com/plantarena/tournaments/adapter/out/persistence/JdbcTournamentRepository.java
rm tournaments-service/src/main/java/com/plantarena/tournaments/adapter/out/persistence/JdbcTagRepository.java
```

- [ ] **Step 2: `build.gradle.kts`**

```kotlin
plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":shared-lib"))
    implementation(project(":shared-webflux"))
    implementation(libs.spring.boot.webflux)
    implementation(libs.spring.boot.jpa)
    implementation(libs.spring.boot.jdbc)
    implementation(libs.spring.boot.validation)
    implementation(libs.spring.boot.actuator)
    implementation(libs.eureka.client)
    implementation(libs.config.client)
    implementation(libs.openfeign)
    implementation(libs.circuitbreaker)
    implementation(libs.openapi.webflux)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.webflux.test)
    testImplementation(libs.spring.boot.jdbc.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.awaitility)
    testImplementation(libs.archunit)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.springCloud.get()}")
    }
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*IT.class")
    shouldRunAfter(tasks.test)
}
tasks.test { exclude("**/*IT.class") }
tasks.named("check") { dependsOn(integrationTestTask) }
tasks.jar { enabled = false }
```
NOTE: `spring-boot-starter-data-jpa` brings Hibernate + `JpaTransactionManager`; the remaining `JdbcClient` repositories participate in JPA transactions on the same `DataSource` (documented pattern, ADR-014). `spring-boot-starter-webflux` (NOT webmvc) — Netty stack.

- [ ] **Step 3: JPA entities + repositories for Tournament and Tag**

`adapter/out/persistence/jpa/TournamentEntity.java`:
```java
package com.plantarena.tournaments.adapter.out.persistence.jpa;

import com.plantarena.tournaments.domain.CancelReason;
import com.plantarena.tournaments.domain.TournamentStatus;
import com.plantarena.tournaments.domain.TournamentType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tournament", schema = "tournaments")
@Getter
@Setter
public class TournamentEntity {

    @Id
    private UUID id;

    @Column(name = "creator_id", nullable = false)
    private UUID creatorId;

    private String name;
    private String description;

    @Enumerated(EnumType.STRING)
    private TournamentType type;

    @Enumerated(EnumType.STRING)
    private TournamentStatus status;

    private String algorithm;

    @Column(name = "registration_deadline")
    private Instant registrationDeadline;

    @Convert(converter = DurationSecondsConverter.class)
    @Column(name = "round_duration_seconds")
    private Duration roundDuration;

    @Column(name = "elimination_fraction")
    private double eliminationFraction;

    @Column(name = "min_participants")
    private int minParticipants;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason")
    private CancelReason cancelReason;

    @ElementCollection
    @CollectionTable(name = "tournament_tag", schema = "tournaments",
        joinColumns = @JoinColumn(name = "tournament_id"))
    @Column(name = "tag_id", nullable = false)
    private Set<UUID> tagIds = new HashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;
}
```

`adapter/out/persistence/jpa/DurationSecondsConverter.java`:
```java
package com.plantarena.tournaments.adapter.out.persistence.jpa;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.Duration;

@Converter
public class DurationSecondsConverter implements AttributeConverter<Duration, Long> {

    @Override
    public Long convertToDatabaseColumn(Duration duration) {
        return duration == null ? null : duration.toSeconds();
    }

    @Override
    public Duration convertToEntityAttribute(Long seconds) {
        return seconds == null ? null : Duration.ofSeconds(seconds);
    }
}
```

`adapter/out/persistence/jpa/TagEntity.java`:
```java
package com.plantarena.tournaments.adapter.out.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tag", schema = "tournaments")
@Getter
@Setter
public class TagEntity {

    @Id
    private UUID id;

    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
```

`adapter/out/persistence/jpa/TournamentJpaRepository.java` (Spring Data):
```java
package com.plantarena.tournaments.adapter.out.persistence.jpa;

import com.plantarena.tournaments.domain.TournamentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TournamentJpaRepository
        extends JpaRepository<TournamentEntity, UUID>, JpaSpecificationExecutor<TournamentEntity> {

    @Query("""
        select t from TournamentEntity t
        where t.status = :status and t.registrationDeadline <= :now
        order by t.registrationDeadline asc
        """)
    List<TournamentEntity> findDueForStart(@Param("status") TournamentStatus status,
                                           @Param("now") Instant now, Pageable pageable);
}
```

`adapter/out/persistence/jpa/TagJpaRepository.java`:
```java
package com.plantarena.tournaments.adapter.out.persistence.jpa;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagJpaRepository extends JpaRepository<TagEntity, UUID> {
    Optional<TagEntity> findByName(String name);
}
```

- [ ] **Step 4: Domain-port adapters over JPA**

`adapter/out/persistence/JpaTournamentRepository.java` — implements the existing domain port `com.plantarena.tournaments.domain.TournamentRepository` (`save`, `findById`, `delete`, `search(TournamentFilter)`, `count(filter)`, `findDueForStart(now, limit)`):
- `save`: if `jpa.existsById(t.id())` → load entity, copy mutable fields from the domain object (name, description, status, type, algorithm, registrationDeadline, roundDuration, eliminationFraction, minParticipants, cancelReason, tagIds), keep `@Version`; else create new entity from domain (version starts 0). After `jpa.save`, map back to domain with the post-save version.
- `search`/`count`: build a `Specification<TournamentEntity>` from `TournamentFilter` (userId → `creatorId` unless admin; status; tagId → `tagIds` membership: `:tagId member of t.tagIds` via criteria `isMember`), `PageRequest.of(offset/size)` for search.
- `findDueForStart`: `jpa.findDueForStart(REGISTRATION_OPEN, now, PageRequest.of(0, limit))` mapped to domain.
- Mapping to/from domain `Tournament` uses the domain's `restore(...)` factory and getters (check the real factory signature in the copied domain code).

`adapter/out/persistence/JpaTagRepository.java` — implements domain port `TagRepository` (`save`, `findById`, `findByName`, `findAll(paged)`, `count`, `delete`, `isUsedByTournament`): `isUsedByTournament` via a `@Query("select count(t) > 0 from TournamentEntity t where :tagId member of t.tagIds")` on `TournamentJpaRepository`.

- [ ] **Step 5: Config + yml + application class**

`TournamentsServiceApplication.java`:
```java
package com.plantarena.tournaments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.plantarena.tournaments", "com.plantarena.shared.webflux"})
@EnableFeignClients(basePackages = "com.plantarena.tournaments.api.client")
public class TournamentsServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TournamentsServiceApplication.class, args);
    }
}
```

`tournaments-service/.../tournaments/config/TournamentsServiceConfig.java`: `@Configuration @EnableScheduling @EnableConfigurationProperties({GlobalCompetitionSettings.class, GuestSessionsSettings.class})`, beans: `Clock utcClock()`, `FixedWindowRateLimiter guestRateLimiter(Clock)` (copy from monolith `TournamentsWiringConfig`), `IntegrationEventPublisher` bridge (copy from `EventWiringConfig`), OpenAPI bean.

`application.yml`:
```yaml
spring:
  application:
    name: tournaments-service
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/tournaments}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: tournaments
server:
  port: 8086
  forward-headers-strategy: framework
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka}
  instance:
    prefer-ip-address: true
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,circuitbreakers
plantarena:
  global:
    epoch-duration: 24h
    final-window-duration: 6h
  guests:
    session-ttl: 24h
    session-creation-limit-per-minute: 10
    vote-limit-per-minute: 30
feign:
  circuitbreaker:
    enabled: true
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
    instances:
      identity-service:
        baseConfig: default
      plants-service:
        baseConfig: default
      geo-service:
        baseConfig: default
      feed-service:
        baseConfig: default
```

- [ ] **Step 6: Tests (part 1)**

- Keep: all domain tests, all application tests + fakes (ports unchanged), JDBC contract ITs for entry/invitation/window/epoch/guest-session.
- Replace `JdbcTournamentRepositoryContractIT` → `JpaTournamentRepositoryContractIT` and `JdbcTagRepositoryContractIT` → `JpaTagRepositoryContractIT`: same abstract contracts (`TournamentRepositoryContractTest`, `TagRepositoryContractTest`), now against the JPA adapters on Testcontainers PG (with Flyway migrations). Delete the old JDBC impls' ITs.
- `application-test.yml`: eureka/config off + `plantarena.*` test values (copy from monolith test resources if any).
- Adapted `AbstractIntegrationTest` — WebFlux flavor: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `WebTestClient` (no MockMvc). Provide it now; controller ITs come in Task 12.

- [ ] **Step 7: Verify and commit**

```bash
./gradlew :tournaments-service:test :tournaments-service:integrationTest :tournaments-service:build
git add -A && git commit -m "feat(lab2): tournaments-service :8086 — ядро контекста + JPA (Tournament, Tag) + JDBC-репозитории остальных агрегатов"
```

---

### Task 12: tournaments-service, часть 2 — WebFlux web-слой + реактивный актёр + внутренние эндпоинты

- [ ] **Step 1: Reactive actor infrastructure**

`tournaments-service/.../tournaments/adapter/in/web/ActorContextWebFilter.java`:
```java
package com.plantarena.tournaments.adapter.in.web;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Component
public class ActorContextWebFilter implements WebFilter, Ordered {

    public static final String DEMO_USER_ID_HEADER = "X-Demo-User-Id";
    public static final String ACTOR_ID_CONTEXT_KEY = "plantarena.actorId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst(DEMO_USER_ID_HEADER);
        Context context = Context.of(ACTOR_ID_CONTEXT_KEY, header == null ? "" : header.trim());
        return chain.filter(exchange).contextWrite(context);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
```

`tournaments-service/.../tournaments/adapter/in/web/DemoHeaderReactiveCurrentActorProvider.java`:
```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import com.plantarena.shared.webflux.Blocking;
import com.plantarena.shared.webflux.ReactiveCurrentActorProvider;
import com.plantarena.tournaments.api.client.IdentityDirectoryClient;
import feign.FeignException;
import java.util.EnumSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

@Component
@RequiredArgsConstructor
public class DemoHeaderReactiveCurrentActorProvider implements ReactiveCurrentActorProvider {

    private final IdentityDirectoryClient identityClient;

    @Override
    public Mono<CurrentActor> currentActor() {
        return Mono.deferContextual(this::fromContext);
    }

    private Mono<CurrentActor> fromContext(ContextView contextView) {
        String header = contextView.getOrDefault(ActorContextWebFilter.ACTOR_ID_CONTEXT_KEY, "");
        if (header.isBlank()) {
            return Mono.just(CurrentActor.guest());
        }
        UUID userId;
        try {
            userId = UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            return Mono.error(new NotIdentifiedException("Некорректный X-Demo-User-Id: " + header));
        }
        return Blocking.call(() -> identityClient.getActor(userId))
            .map(view -> {
                EnumSet<AppRole> roles = EnumSet.noneOf(AppRole.class);
                view.roles().forEach(role -> roles.add(AppRole.valueOf(role)));
                return CurrentActor.identified(view.userId(), roles);
            })
            .onErrorMap(FeignException.NotFound.class,
                e -> new NotIdentifiedException("Неизвестный или деактивированный пользователь: " + userId));
    }
}
```

- [ ] **Step 2: Convert the 7 controllers + exception handler to WebFlux**

Pattern (apply to EVERY endpoint of `TournamentController`, `InvitationController`, `VotingController`, `TagController`, `GuestSessionController`, `GlobalController`, `DemoJobsController`):
- Return type `Mono<X>` (or `Mono<ResponseEntity<X>>`, `Mono<Void>` for 204).
- First statement: `return actors.currentActor().flatMap(actor -> Blocking.call(() -> <existing blocking use-case call with actor>) .map(...));`
- `HttpServletRequest` (GuestSessionController client IP) → `ServerHttpRequest` (`request.getRemoteAddress()`); `@RequestHeader X-Guest-Token` stays.
- Pagination `PaginationParams` usage unchanged.

Full example — one endpoint of `TournamentController` (all others follow mechanically, preserving paths/bodies/statuses/headers exactly):
```java
@PostMapping
public Mono<ResponseEntity<TournamentResponse>> create(@Valid @RequestBody CreateTournamentRequest request) {
    return actors.currentActor()
        .flatMap(actor -> Blocking.call(() -> createTournament.create(actor, toCommand(request)))
            .map(t -> ResponseEntity
                .created(URI.create("/api/v1/tournaments/" + t.id()))
                .body(TournamentResponse.from(t))));
}
```
(Use the existing private mapping helpers from the copied controllers unchanged; only signatures and the reactive wrapper change. `X-Total-Count` headers: keep via `ResponseEntity` builders as before.)

`TournamentsExceptionHandler` → WebFlux: replace `HttpServletRequest` params with `ServerWebExchange`, traceId from `exchange.getAttribute(TraceIdWebFilter.TRACE_ID_ATTRIBUTE)`, keep ALL ~28 exception→ApiError mappings and codes verbatim (incl. `Retry-After` header for `RateLimitExceededException` via `ResponseEntity.status(429).header(...)`).

- [ ] **Step 3: Internal endpoints**

`tournaments-service/.../tournaments/adapter/in/web/InternalFeedDirectoryController.java`:
```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.api.FeedDirectory;
import com.plantarena.tournaments.api.GuestSessionDirectory;
import com.plantarena.tournaments.application.WindowNotFoundException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalFeedDirectoryController {

    private final FeedDirectory feedDirectory;
    private final GuestSessionDirectory guestSessionDirectory;

    @GetMapping("/feed/voted-entry-ids")
    public Mono<Set<UUID>> votedEntryIds(@RequestParam("subjectKey") String subjectKey) {
        return Mono.fromSupplier(() -> feedDirectory.findVotedEntryIdsInOpenWindows(subjectKey));
    }

    @GetMapping("/feed/participated-tournament-ids")
    public Mono<Set<UUID>> participatedTournamentIds(@RequestParam("userId") UUID userId) {
        return Mono.fromSupplier(() -> feedDirectory.findParticipatedTournamentIds(userId));
    }

    @GetMapping("/guest-sessions/active")
    public Mono<ActiveSessionResponse> activeSession(@RequestParam("token") String rawToken) {
        return Mono.fromSupplier(() -> guestSessionDirectory.activeSessionId(rawToken))
            .map(id -> id.orElseThrow(() -> new GuestSessionNotFound(rawToken)))
            .map(ActiveSessionResponse::new);
    }

    public record ActiveSessionResponse(UUID sessionId) {}
    public static class GuestSessionNotFound extends RuntimeException {
        public GuestSessionNotFound(String token) {
            super("Гостевая сессия не найдена или истекла");
        }
    }
}
```
Add `@ExceptionHandler(GuestSessionNotFound.class)` → 404 `GUEST_SESSION_NOT_FOUND` in `TournamentsExceptionHandler` (or a small internal advice).

`tournaments-service/.../tournaments/adapter/in/web/InternalEventsController.java` (replaces `PlantModerationDecidedHandler`):
```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.application.port.in.OnPlantModerationDecidedUseCase;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal/events")
@RequiredArgsConstructor
public class InternalEventsController {

    private final OnPlantModerationDecidedUseCase onPlantModerationDecided;

    @PostMapping("/plant-moderation-decided")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> plantModerationDecided(@RequestBody PlantModerationDecidedRequest request) {
        return Mono.fromRunnable(() ->
            onPlantModerationDecided.onPlantModerationDecided(request.plantId(), request.decision()));
    }

    public record PlantModerationDecidedRequest(UUID plantId, String decision) {}
}
```
NOTE: `Mono.fromRunnable` runs on the event loop — the use case is blocking (JDBC). Wrap: `Blocking.call(() -> { onPlantModerationDecided...; return null; }).then()`. Same for the directory endpoints above — wrap the blocking calls in `Blocking.call`/`Mono.fromSupplier(...).subscribeOn(boundedElastic)` — use `Blocking.call` consistently.

- [ ] **Step 4: Verify and commit**

```bash
./gradlew :tournaments-service:test :tournaments-service:build
git add -A && git commit -m "feat(lab2): tournaments-service — WebFlux web-слой, реактивный актёр, /internal (feed-directory, events)"
```

---

### Task 13: tournaments-service, часть 3 — Feign ACL + Circuit Breaker + relay в feed

- [ ] **Step 1: Feign clients** (`tournaments-service/.../tournaments/api/client/`)

`IdentityDirectoryClient.java` — like media's (Task 7 Step 4) plus location:
```java
@FeignClient(name = "identity-service")
public interface IdentityDirectoryClient {
    @GetMapping("/internal/actors/{userId}")
    ActorView getActor(@PathVariable("userId") UUID userId);

    @GetMapping("/internal/users/{userId}/profile")
    UserProfileView getProfile(@PathVariable("userId") UUID userId);

    @GetMapping("/internal/users/{userId}/location")
    UserLocationView getLocation(@PathVariable("userId") UUID userId);

    record ActorView(UUID userId, List<String> roles) {}
    record UserProfileView(UUID id, String displayName, boolean active) {}
    record UserLocationView(double latitude, double longitude, long locationVersion) {}
}
```

`PlantsDirectoryClient.java`:
```java
@FeignClient(name = "plants-service")
public interface PlantsDirectoryClient {
    @GetMapping("/internal/plants/{plantId}")
    PlantView getPlant(@PathVariable("plantId") UUID plantId);

    @PostMapping("/internal/plants/reservations")
    ReservationResponse reserve(@RequestBody ReserveRequest request);

    @PostMapping("/internal/plants/reservations/{reservationId}/confirm")
    void confirm(@PathVariable("reservationId") UUID reservationId, @RequestBody ConfirmRequest request);

    @PostMapping("/internal/plants/reservations/{reservationId}/release")
    void release(@PathVariable("reservationId") UUID reservationId);

    @PostMapping("/internal/plants/{plantId}/death")
    void registerDeath(@PathVariable("plantId") UUID plantId, @RequestBody DeathRequest request);

    record PlantView(UUID id, UUID ownerId, UUID assetId, String title,
                     String moderationStatus, String lifeStatus) {}
    record ReserveRequest(UUID ownerId, UUID plantId, UUID idempotencyKey) {}
    record ReservationResponse(UUID reservationId) {}
    record ConfirmRequest(UUID ownerId, UUID plantId) {}
    record DeathRequest(String kind, Instant cooldownExpiresAt, String reason, UUID sourceEntryId) {}
}
```

`GeoClustersClient.java`:
```java
@FeignClient(name = "geo-service")
public interface GeoClustersClient {
    @PostMapping("/internal/clusters/assignments")
    List<AssignedClusterView> assign(@RequestBody AssignmentRequest request);

    record AssignmentRequest(UUID epochId, List<MemberView> members) {}
    record MemberView(UUID entryId, UUID userId, double latitude, double longitude, long locationVersion) {}
    record AssignedClusterView(UUID snapshotId, String clusterKey, List<UUID> entryIds) {}
}
```

`FeedEventsClient.java`:
```java
@FeignClient(name = "feed-service")
public interface FeedEventsClient {
    @PostMapping("/internal/feed/projections")
    void windowOpened(@RequestBody WindowProjectionRequest request);

    @DeleteMapping("/internal/feed/projections/{windowId}")
    void windowClosed(@PathVariable("windowId") UUID windowId);

    record WindowProjectionRequest(UUID windowId, UUID tournamentId, String scope, UUID clusterId,
                                   Instant closesAt, List<CardSeedView> cards) {}
    record CardSeedView(UUID entryId, UUID userId, UUID plantId, Instant joinedAt) {}
}
```

- [ ] **Step 2: Error translation helper**

`tournaments-service/.../tournaments/api/client/ApiErrorDecoder.java` — static helper decoding a `FeignException`'s body JSON into `com.plantarena.shared.web.ApiError` (Jackson `ObjectMapper`), used by adapters:
```java
package com.plantarena.tournaments.api.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plantarena.shared.web.ApiError;
import feign.FeignException;
import java.util.Optional;

public final class ApiErrorDecoder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiErrorDecoder() {}

    public static Optional<ApiError> decode(FeignException e) {
        try {
            return Optional.ofNullable(MAPPER.readValue(e.contentUTF8(), ApiError.class));
        } catch (Exception parseFailure) {
            return Optional.empty();
        }
    }
}
```
NOTE: `ApiError` may need `retryAt` handling — Jackson maps records by component name; `Instant` needs `jackson-datatype-jsr310` (present via Boot). If `ApiError` lacks a no-arg creator it's a record — fine.

- [ ] **Step 3: HTTP adapters for the 6 outbound ports** (reference: the deleted in-process adapters in `monolith-reference/.../tournaments/adapter/out/` — replicate their translations exactly)

`adapter/out/identity/HttpParticipantDirectory.java` (port `ParticipantDirectoryGateway.isKnownUser`): `getProfile(userId)` → 404 ⇒ `false`, else `active`.
`adapter/out/identity/HttpParticipantLocations.java` (port `ParticipantLocationsGateway.findLocation`): `getLocation(userId)` → 404 ⇒ `Optional.empty()`, else map.
`adapter/out/plants/HttpPlantDirectory.java` (port `PlantDirectoryGateway.findById`): `getPlant` → 404 ⇒ `Optional.empty()`, else `PlantSnapshot(plantId, ownerId, approved = "APPROVED".equals(moderationStatus))`.
`adapter/out/plants/HttpPlantEligibility.java` (port `PlantEligibilityGateway`): `reserve/confirm/release` via client; error mapping with `ApiErrorDecoder`: code `PLANT_NOT_ELIGIBLE` → `PlantNotReservableException` (keep `retryAt`), `RESERVATION_CONFLICT` → `ImageAlreadyReservedException`, `PLANT_NOT_FOUND` → `InvitedPlantNotFoundException` (match the exact codes produced by plants' `PlantExceptionHandler` — check them in the copied plants code and in `monolith-reference`).
`adapter/out/plants/HttpPlantLifecycle.java` (port `PlantLifecycleGateway.registerDeath`): map `RestrictionKind` by name.
`adapter/out/geo/HttpClusteringGateway.java` (port `ClusteringGateway.assignClusters`): map `MemberLocation` → `GeoClustersClient.MemberView`, response → `AssignedCluster(snapshotId, clusterKey, entryIds)`.
`adapter/out/abuse/LoggingAbuseSignals.java` — copy unchanged from monolith.

- [ ] **Step 4: Circuit Breaker fallbacks (acceptance criterion 5)**

Add explicit fallbacks demonstrating the mechanism on the identity/plants/geo clients. Create `tournaments-service/.../tournaments/application/DependencyUnavailableException.java`:
```java
package com.plantarena.tournaments.application;

public class DependencyUnavailableException extends RuntimeException {
    public DependencyUnavailableException(String service, Throwable cause) {
        super("Зависимый сервис недоступен: " + service, cause);
    }
}
```
Map it → 503 `DEPENDENCY_UNAVAILABLE` in `TournamentsExceptionHandler`. Then configure Feign fallbacks — with `feign.circuitbreaker.enabled=true` register fallback beans: for each client create a `@Component` fallback class (e.g. `IdentityDirectoryClientFallback implements IdentityDirectoryClient`) whose every method throws `new DependencyUnavailableException("identity-service", null)`, and reference it: `@FeignClient(name = "identity-service", fallback = IdentityDirectoryClientFallback.class)`. Do the same for `PlantsDirectoryClient`, `GeoClustersClient`, `FeedEventsClient`. (Fallbacks fire when the CB is open OR the call fails — observable via 503 DEPENDENCY_UNAVAILABLE and `/actuator/circuitbreakers`.)
NOTE: fallback beans need the CB to wrap calls — verify `spring-cloud-starter-circuitbreaker-resilience4j` brings `resilience4j-spring-boot-3` (check `./gradlew :tournaments-service:dependencies --configuration runtimeClasspath | grep resilience4j`). If `resilience4j-spring-boot` is absent, add `implementation("io.github.resilience4j:resilience4j-spring-boot-3:2.3.0")` (check latest 2.x on Maven Central) so `resilience4j.circuitbreaker.instances.*` are honored.

- [ ] **Step 5: Relay to feed**

`tournaments-service/.../tournaments/adapter/out/events/FeedProjectionRelay.java`:
```java
package com.plantarena.tournaments.adapter.out.events;

import com.plantarena.tournaments.adapter.out.events.mapper.WindowProjectionMapper;
import com.plantarena.tournaments.api.client.FeedEventsClient;
import com.plantarena.tournaments.api.event.EntryEliminatedEvent;
import com.plantarena.tournaments.api.event.InvitationCreatedEvent;
import com.plantarena.tournaments.api.event.TournamentFinishedEvent;
import com.plantarena.tournaments.api.event.TournamentStartedEvent;
import com.plantarena.tournaments.api.event.VotingWindowClosedEvent;
import com.plantarena.tournaments.api.event.VotingWindowOpenedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class FeedProjectionRelay {

    private final FeedEventsClient feedEventsClient;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWindowOpened(VotingWindowOpenedEvent event) {
        try {
            feedEventsClient.windowOpened(WindowProjectionMapper.toRequest(event));
        } catch (Exception e) {
            log.warn("Прокция окна {} не доставлена в feed-service: {}", event.payload().windowId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWindowClosed(VotingWindowClosedEvent event) {
        try {
            feedEventsClient.windowClosed(event.payload().windowId());
        } catch (Exception e) {
            log.warn("Закрытие окна {} не доставлено в feed-service: {}", event.payload().windowId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOther(TournamentStartedEvent e) { log.debug("TournamentStarted получателей нет (lab2)"); }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOther(TournamentFinishedEvent e) { log.debug("TournamentFinished получателей нет (lab2)"); }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOther(EntryEliminatedEvent e) { log.debug("EntryEliminated получателей нет (lab2)"); }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOther(InvitationCreatedEvent e) { log.debug("InvitationCreated получателей нет (lab2)"); }
}
```
`WindowProjectionMapper.toRequest(event)` — small mapper: `new FeedEventsClient.WindowProjectionRequest(windowId, tournamentId, scope.name(), clusterId, closesAt, participants.map(p -> new CardSeedView(p.entryId(), p.userId(), p.plantId(), p.joinedAt())))` — match the real `VotingWindowOpenedEvent.Payload` field names from the copied code (inline it in the relay if you prefer; no separate mapper file needed).

- [ ] **Step 6: Verify and commit**

```bash
./gradlew :tournaments-service:test :tournaments-service:integrationTest :tournaments-service:build
git add -A && git commit -m "feat(lab2): tournaments-service — Feign ACL (identity/plants/geo/feed) + Circuit Breaker + relay"
```

---

### Task 14: feed-service (Reactor + JDBC)

- [ ] **Step 1: Copy the context**

```bash
mkdir -p feed-service/src/main/java/com/plantarena feed-service/src/main/resources/db/migration
cp -r monolith-reference/src/main/java/com/plantarena/feed feed-service/src/main/java/com/plantarena/
cp monolith-reference/src/main/resources/db/migration/feed/*.sql feed-service/src/main/resources/db/migration/
mkdir -p feed-service/src/test/java/com/plantarena
cp -r monolith-reference/src/test/java/com/plantarena/feed feed-service/src/test/java/com/plantarena/
cp -r monolith-reference/src/test/java/com/plantarena/support feed-service/src/test/java/com/plantarena/
rm -rf feed-service/src/main/java/com/plantarena/feed/adapter/in/events
rm -rf feed-service/src/main/java/com/plantarena/feed/adapter/out/identity
rm -rf feed-service/src/main/java/com/plantarena/feed/adapter/out/plants
rm -rf feed-service/src/main/java/com/plantarena/feed/adapter/out/tournaments
```

- [ ] **Step 2: `build.gradle.kts`** — copy tournaments' build file (Task 11 Step 2) minus `libs.spring.boot.jpa` (feed uses JdbcClient only): `spring-boot-webflux`, `spring-boot-jdbc`, validation, actuator, eureka, config, openfeign, circuitbreaker, openapi-webflux, flyway, postgresql, tests (webflux-test, jdbc-test, testcontainers, archunit).

- [ ] **Step 3: Application class** — `FeedServiceApplication`, `scanBasePackages = {"com.plantarena.feed", "com.plantarena.shared.webflux"}`, `@EnableFeignClients(basePackages = "com.plantarena.feed.api.client")`.

- [ ] **Step 4: Feign clients + adapters**

`feed-service/.../feed/api/client/IdentityDirectoryClient.java` — copy of tournaments' (Task 13 Step 1, profile + actor).
`feed-service/.../feed/api/client/PlantsDirectoryClient.java`:
```java
@FeignClient(name = "plants-service")
public interface PlantsDirectoryClient {
    @GetMapping("/internal/plants/{plantId}")
    PlantView getPlant(@PathVariable("plantId") UUID plantId);

    record PlantView(UUID id, UUID ownerId, UUID assetId, String title,
                     String moderationStatus, String lifeStatus) {}
}
```
`feed-service/.../feed/api/client/TournamentsDirectoryClient.java`:
```java
@FeignClient(name = "tournaments-service")
public interface TournamentsDirectoryClient {
    @GetMapping("/internal/feed/voted-entry-ids")
    Set<UUID> votedEntryIds(@RequestParam("subjectKey") String subjectKey);

    @GetMapping("/internal/feed/participated-tournament-ids")
    Set<UUID> participatedTournamentIds(@RequestParam("userId") UUID userId);

    @GetMapping("/internal/guest-sessions/active")
    ActiveSessionResponse activeSession(@RequestParam("token") String rawToken);

    record ActiveSessionResponse(UUID sessionId) {}
}
```

Adapters (reference: deleted in-process adapters):
- `adapter/out/identity/HttpOwnerDirectory.java` — port `OwnerDirectory.findOwner` → `getProfile` 404 ⇒ `Optional.empty()`, else `OwnerView(userId, displayName)`.
- `adapter/out/plants/HttpPlantCatalog.java` — port `PlantCatalog.findPlant` → `getPlant` 404 ⇒ empty, else `PlantView(plantId, assetId, title, lifeStatus)`.
- `adapter/out/tournaments/HttpVotingDirectory.java` — port `VotingDirectory` both methods via client.
- `adapter/out/tournaments/HttpGuestSessions.java` — port `GuestSessions.activeSessionId` → 404 ⇒ `Optional.empty()`, else session id.
- Copy `ApiErrorDecoder` (Task 13 Step 2) into `com.plantarena.feed.api.client` if needed for error mapping (mostly 404-mapping here).
- Add `DependencyUnavailableException` + fallbacks for the three clients (same pattern as Task 13 Step 4), mapped → 503 in `FeedExceptionHandler`.

- [ ] **Step 5: WebFlux controller + internal projection endpoint**

`FeedController` — convert to WebFlux (single endpoint):
```java
@GetMapping
public Mono<FeedPageResponse> feed(@RequestParam(value = "limit", required = false) Integer limit,
                                   @RequestParam(value = "cursor", required = false) String cursor,
                                   @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
    return actors.currentActor()
        .flatMap(actor -> Blocking.call(() -> getFeed.get(actor, guestToken, limit, cursor))
            .map(FeedPageResponse::from));
}
```
`FeedExceptionHandler` → WebFlux (`ServerWebExchange` instead of `HttpServletRequest`; keep 400/410 codes).

`feed-service/.../feed/adapter/in/web/InternalProjectionController.java` (replaces the two event handlers):
```java
package com.plantarena.feed.adapter.in.web;

import com.plantarena.feed.application.port.in.ProjectWindowUseCase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal/feed/projections")
@RequiredArgsConstructor
public class InternalProjectionController {

    private final ProjectWindowUseCase projectWindow;

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> onWindowOpened(@RequestBody WindowProjectionRequest request) {
        return Blocking.call(() -> {
            projectWindow.onWindowOpened(request.toCommand());
            return null;
        }).then();
    }

    @DeleteMapping("/{windowId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> onWindowClosed(@PathVariable UUID windowId) {
        return Blocking.call(() -> {
            projectWindow.onWindowClosed(windowId);
            return null;
        }).then();
    }

    public record WindowProjectionRequest(UUID windowId, UUID tournamentId, String scope, UUID clusterId,
                                          Instant closesAt, List<CardSeed> cards) {
        public record CardSeed(UUID entryId, UUID userId, UUID plantId, Instant joinedAt) {}

        public ProjectWindowUseCase.WindowCardsCommand toCommand() {
            return new ProjectWindowUseCase.WindowCardsCommand(windowId, tournamentId, scope, clusterId,
                closesAt, cards.stream().map(c -> new ProjectWindowUseCase.CardSeed(
                    c.entryId(), c.userId(), c.plantId(), c.joinedAt())).toList());
        }
    }
}
```
NOTE: match the real `WindowCardsCommand`/`CardSeed` signatures from the copied port (field order/names may differ — align exactly; also `scope` may be an enum in the command — if so, `WindowScope.valueOf(scope)`).

- [ ] **Step 6: Reactive actor** — copy `ActorContextWebFilter` + `DemoHeaderReactiveCurrentActorProvider` from tournaments (Task 12 Step 1) into `com.plantarena.feed.adapter.in.web`, importing feed's `IdentityDirectoryClient`.

- [ ] **Step 7: Config + yml**

`feed-service/.../feed/config/FeedServiceConfig.java`: `@Configuration @EnableConfigurationProperties(FeedSettings.class)`, beans: `Clock`, `FeedCursorCodec(settings)`, `FeedOrdering()` (copy from monolith `FeedWiringConfig`), OpenAPI bean.

`application.yml`: identity's template with `name: feed-service`, `port: 8087`, db `feed`, `flyway.schemas: feed`, plus:
```yaml
plantarena:
  feed:
    cursor-secret: ${FEED_CURSOR_SECRET:dev-only-cursor-secret}
    cursor-ttl: 1h
feign:
  circuitbreaker:
    enabled: true
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
    instances:
      tournaments-service:
        baseConfig: default
      plants-service:
        baseConfig: default
      identity-service:
        baseConfig: default
```

- [ ] **Step 8: Tests**

- Keep: domain tests, `FeedServiceTest`, `FeedProjectionServiceTest`, `FeedCursorCodecTest`, fakes, `JdbcFeedCardRepositoryContractIT` + in-memory contract pair.
- Delete `FeedApiIT`, `FeedCursorApiIT` (cross-context: guest tokens + tournaments).
- `application-test.yml` + WebFlux `AbstractIntegrationTest` (RANDOM_PORT + WebTestClient) + reactive test actor stub (`TestHeaderReactiveCurrentActorProvider` — like Task 12's provider but resolving `X-Demo-User-Id` + `X-Demo-Roles` locally, no Feign).
- Add `architecture/FeedLayerRulesTest`.

- [ ] **Step 9: Verify and commit**

```bash
./gradlew :feed-service:test :feed-service:integrationTest :feed-service:build
git add -A && git commit -m "feat(lab2): feed-service :8087 — WebFlux + JdbcClient, Feign ACL, /internal/feed/projections"
```

---

### Task 15: docker-compose + Dockerfiles + скрипты БД

**Files:** Create `docker-compose.yml`, `scripts/docker/init-databases.sql`, `scripts/local/create-databases.sql`, `<svc>/Dockerfile` ×10.

- [ ] **Step 1: DB init scripts**

`scripts/docker/init-databases.sql` (mounted into the postgres container; runs once on empty volume):
```sql
CREATE DATABASE identity;
CREATE DATABASE media;
CREATE DATABASE plants;
CREATE DATABASE moderation;
CREATE DATABASE tournaments;
CREATE DATABASE geo;
CREATE DATABASE feed;
```
`scripts/local/create-databases.sql` — same content (for local `psql -U postgres -f`).

- [ ] **Step 2: Dockerfile template** (one per Boot module: eureka-server, config-server, api-gateway, 7 services; context = repo root)

`<module>/Dockerfile`:
```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY . .
RUN ./gradlew --no-daemon -q :<module>:bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/<module>/build/libs/*.jar app.jar
EXPOSE <port>
ENTRYPOINT ["java", "-jar", "app.jar"]
```
`moderation-service/Dockerfile` additionally downloads the ONNX model (copy the approach from `monolith-reference/Dockerfile`: run the `downloadModerationModel` gradle task in the build stage and `COPY --from=build /build/moderation-service/build/models/ /app/models/`, set `ENV MODERATION_MODEL_PATH=/app/models/mobilenetv2-1.0.onnx`).

- [ ] **Step 3: `docker-compose.yml`**

```yaml
services:
  postgres:
    image: postgres:17.5-alpine
    environment:
      POSTGRES_USER: ${DB_USERNAME:-postgres}
      POSTGRES_PASSWORD: ${DB_PASSWORD:-postgres}
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./scripts/docker/init-databases.sql:/docker-entrypoint-initdb.d/init-databases.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USERNAME:-postgres}"]
      interval: 5s
      timeout: 3s
      retries: 10

  eureka-server:
    build: { context: ., dockerfile: eureka-server/Dockerfile }
    ports: ["8761:8761"]
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8761/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 20
      start_period: 30s

  config-server:
    build: { context: ., dockerfile: config-server/Dockerfile }
    ports: ["8888:8888"]
    depends_on:
      eureka-server:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8888/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 20
      start_period: 30s

  api-gateway:
    build: { context: ., dockerfile: api-gateway/Dockerfile }
    ports: ["8080:8080"]
    environment:
      EUREKA_URL: http://eureka-server:8761/eureka
    depends_on:
      eureka-server:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 30
      start_period: 40s

  identity-service:
    build: { context: ., dockerfile: identity-service/Dockerfile }
    ports: ["8081:8081"]
    environment: &service-env
      SPRING_CONFIG_IMPORT: configserver:http://config-server:8888
      EUREKA_URL: http://eureka-server:8761/eureka
      DB_USERNAME: ${DB_USERNAME:-postgres}
      DB_PASSWORD: ${DB_PASSWORD:-postgres}
      BOOTSTRAP_ADMIN_EMAIL: ${BOOTSTRAP_ADMIN_EMAIL:-}
      BOOTSTRAP_ADMIN_PASSWORD: ${BOOTSTRAP_ADMIN_PASSWORD:-}
      BOOTSTRAP_ADMIN_DISPLAY_NAME: ${BOOTSTRAP_ADMIN_DISPLAY_NAME:-Admin}
    depends_on:
      postgres:
        condition: service_healthy
      eureka-server:
        condition: service_healthy
      config-server:
        condition: service_healthy

  media-service:
    build: { context: ., dockerfile: media-service/Dockerfile }
    ports: ["8082:8082"]
    environment:
      <<: *service-env
    volumes:
      - media-storage:/app/storage/media
    depends_on:
      postgres: { condition: service_healthy }
      eureka-server: { condition: service_healthy }
      config-server: { condition: service_healthy }

  plants-service:
    build: { context: ., dockerfile: plants-service/Dockerfile }
    ports: ["8083:8083"]
    environment:
      <<: *service-env
    depends_on:
      postgres: { condition: service_healthy }
      eureka-server: { condition: service_healthy }
      config-server: { condition: service_healthy }

  moderation-service:
    build: { context: ., dockerfile: moderation-service/Dockerfile }
    ports: ["8084:8084"]
    environment:
      <<: *service-env
    depends_on:
      postgres: { condition: service_healthy }
      eureka-server: { condition: service_healthy }
      config-server: { condition: service_healthy }

  geo-service:
    build: { context: ., dockerfile: geo-service/Dockerfile }
    ports: ["8085:8085"]
    environment:
      <<: *service-env
    depends_on:
      postgres: { condition: service_healthy }
      eureka-server: { condition: service_healthy }
      config-server: { condition: service_healthy }

  tournaments-service:
    build: { context: ., dockerfile: tournaments-service/Dockerfile }
    ports: ["8086:8086"]
    environment:
      <<: *service-env
    depends_on:
      postgres: { condition: service_healthy }
      eureka-server: { condition: service_healthy }
      config-server: { condition: service_healthy }

  feed-service:
    build: { context: ., dockerfile: feed-service/Dockerfile }
    ports: ["8087:8087"]
    environment:
      <<: *service-env
      FEED_CURSOR_SECRET: ${FEED_CURSOR_SECRET:-dev-only-cursor-secret}
    depends_on:
      postgres: { condition: service_healthy }
      eureka-server: { condition: service_healthy }
      config-server: { condition: service_healthy }

volumes:
  pgdata:
  media-storage:
```
NOTE: `SPRING_CONFIG_IMPORT: configserver:...` (non-optional) in docker — config server must be up (healthcheck-gated). The `&service-env`/`<<: *service-env` YAML anchors keep it DRY; if compose complains about anchor merging with `depends_on`, inline the env per service.

- [ ] **Step 4: Validate and commit**

```bash
docker compose config -q && echo OK
./gradlew build -x test   # full monorepo compile check
git add -A && git commit -m "build(lab2): docker-compose (postgres 7 БД + 10 сервисов) + Dockerfile на модуль"
```

---

### Task 16: Документация + CI

- [ ] **Step 1: `docs/architecture.md`** — mermaid diagram: Gateway → services → Eureka/Config; Feign dependency graph (identity←{media,plants,tournaments,feed}; media←{plants,moderation}; plants←{moderation,tournaments,feed}; geo←tournaments; tournaments←feed); DB-per-service table; ports table; event delivery table (plants→tournaments HTTP POST, tournaments→feed HTTP POST, moderation polling).
- [ ] **Step 2: `docs/domain/context-map.md`** — update the "Эволюция" section: lab2 state (Feign/HTTP instead of in-process ACL; events over HTTP; polling for moderation), keep the lab4 Kafka plan.
- [ ] **Step 3: `docs/domain/adr/`** — add ADR-014 (reactive+JPA pattern; R2DBC geo), ADR-015 (events over HTTP + moderation polling), ADR-016 (demo auth across services, internal endpoints trust), ADR-017 (shared module split lib/web/webflux), ADR-018 (ApiError error translation over Feign), ADR-019 (single postgres container, config-repo on classpath). Short format like existing ADRs.
- [ ] **Step 4: `docs/api-contracts/`** — one markdown per internal contract: `identity-internal.md`, `media-internal.md`, `plants-internal.md`, `geo-internal.md`, `tournaments-internal.md`, `feed-internal.md` — endpoint, request/response JSON examples, error codes, which Feign client consumes it.
- [ ] **Step 5: `README.md`** — rewrite for lab2: architecture summary, how to run (`docker compose up --build`, URL map, Eureka/Config/Gateway/Swagger URLs), local dev (create DBs via `scripts/local/create-databases.sql`, `./gradlew :<svc>:bootRun`, infra first), test commands, acceptance checklist. Note Bruno collection works against `:8080` unchanged.
- [ ] **Step 6: `.github/workflows/ci.yml`** — replace `./mvnw verify` with `./gradlew build` (Testcontainers works on ubuntu-latest), drop the maven cache, add gradle cache:
```yaml
name: ci
on: [push, pull_request]
jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21 }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew build
```
- [ ] **Step 7: Update `.env.example`** (DB_USERNAME, DB_PASSWORD, BOOTSTRAP_ADMIN_*, FEED_CURSOR_SECRET) and commit:
```bash
git add -A && git commit -m "docs(lab2): архитектура, context map, api-контракты, ADR-014..019, README, CI на Gradle"
```

---

### Task 17: Финальная верификация + удаление monolith-reference

- [ ] **Step 1: Full build**

```bash
./gradlew clean build   # все 13 модулей: unit + IT (Testcontainers) зелёные
```

- [ ] **Step 2: Smoke test via docker compose** (requires Docker; if unavailable, document and skip with a note)

```bash
docker compose up --build -d
# дождаться здоровья
for p in 8081 8082 8083 8084 8085 8086 8087; do curl -fsS http://localhost:$p/actuator/health; done
```
Acceptance checks:
1. Eureka: `curl http://localhost:8761/eureka/apps` — все 7 доменных сервисов + gateway зарегистрированы.
2. Config: `curl http://localhost:8081/actuator/env | grep postgres` — значения из config-repo.
3. Gateway happy path: `curl -X POST http://localhost:8080/api/v1/users -H 'Content-Type: application/json' -H 'X-Demo-User-Id: <admin-uuid>' -d '{...}'` (bootstrap admin from env) — 201 через gateway.
4. Feign chain: создать турнир (tournaments) с приглашением → подтверждение резервирует растение через plants-service (Feign) — проверить в логах/БД.
5. Circuit Breaker: `docker compose stop identity-service` → запрос к `/api/v1/tournaments` с `X-Demo-User-Id` → 503 `DEPENDENCY_UNAVAILABLE` (fallback), `/actuator/circuitbreakers` на :8086 показывает OPEN; `docker compose start identity-service`.
6. geo: `curl http://localhost:8085/actuator/health` + проверка, что в `geo-service/build.gradle.kts` нет jdbc/jpa стартеров.
7. Swagger: `curl -fsS http://localhost:8080/identity-service/swagger-ui.html` (и для остальных сервисов).
8. Bruno: прогнать пару запросов из `PlantArena API` против `:8080`.
9. `docker compose down -v`.

- [ ] **Step 3: Remove monolith-reference and finalize**

```bash
git rm -r monolith-reference
./gradlew build   # финальная проверка без монолита
git add -A && git commit -m "chore(lab2): удалён monolith-reference — декомпозиция завершена"
```

- [ ] **Step 4: Report** — summarize acceptance criteria status (10 пунктов из спеки) to the user.

---

## Acceptance Criteria Mapping

| # | Критерий | Где |
|---|---|---|
| 1 | 7 сервисов в Eureka | Tasks 6–14 (eureka-client), проверка Task 17 |
| 2 | Конфиг из Config Server | Tasks 4, 6–14, проверка Task 17 |
| 3 | Все запросы через Gateway | Task 5 |
| 4 | Feign: tournaments → identity | Tasks 12–13 |
| 5 | CB + fallback при отказе | Tasks 13–14, проверка Task 17 |
| 6 | geo полностью Reactor+R2DBC | Task 10 |
| 7 | tournaments Reactor + JPA | Tasks 11–13 |
| 8 | docker-compose поднимает всё | Task 15, проверка Task 17 |
| 9 | Swagger через Gateway | Task 5 (direct routes), проверка Task 17 |
| 10 | ./gradlew build без ошибок | Каждый task + Task 17 |

## Self-Review Notes (executors read this)

- Whenever a plan step says "match the real signature from the copied code" — the copied monolith code is the source of truth; read it before writing the adapter. Never invent field/method names.
- `ApiError`'s exact constructor/components: check `shared-lib/src/main/java/com/plantarena/shared/web/ApiError.java` after Task 2 copy.
- If `@TransactionalEventListener(AFTER_COMMIT)` relays fire outside transactions (e.g. `EnsureGlobalCompetitionService`), Spring skips them silently — acceptable (those paths publish no feed events).
- Known risk points to watch: (a) Gateway 5.x property namespace (`spring.cloud.gateway.server.webflux.*`) — verified; (b) JPA `@ElementCollection` on `tournament_tag` — verify contract IT covers tag replace; (c) R2DBC `@Table("geo.cluster_snapshot")` schema-qualified names — verify in IT; (d) Feign `ResponseEntity<byte[]>` for media content; (e) `server.forward-headers-strategy: framework` + springdoc behind StripPrefix — verify swagger loads assets correctly, else document direct-port fallback.
