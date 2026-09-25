# Итерация 3 (plants): Plant, запреты, резервы, PlantEligibility, гибель — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Контекст plants: агрегаты `Plant` (модерация/жизнь, DEAD необратим), `ImageRestriction` (PERMANENT/COOLDOWN), `PlantReservation` (idempotency key + частичный уникальный индекс), опубликованный контракт `plants.api` (`PlantEligibility`, `PlantModeration`, `PlantLifecycle`, события), REST `/api/v1/plants`, задействованность/видимость файлов в media; допущения 1–5 раздела 3 покрыты именованными тестами.

**Architecture:** Модульный монолит, bounded context `plants` (api/domain/application/adapter). plants — customer контекста media: метаданные asset и команды задействованности через `media.api` (ACL в `plants.adapter.out.media`). plants не знает о moderation и tournaments: решения приходят командой `PlantModeration.recordDecision`, гибель — командой `PlantLifecycle.registerDeath`; plants публикует `PlantSubmitted`/`PlantModerationDecided`/`PlantDied` через порт `IntegrationEventPublisher` (in-process). Задействованность файла (запрет DELETE, публичная видимость GET) хранится в media (таблица `asset_claim`), потому что media — upstream и не может зависеть от plants; plants командует media (Customer–Supplier).

**Tech Stack:** Java 21, Spring Boot 4.0.8 (MVC, Data JPA), PostgreSQL 17.5 + Flyway (по контекстам, ADR-003), Testcontainers 2.0.5, ArchUnit 1.5.0, JaCoCo 0.8.15, JUnit Jupiter 6, AssertJ, MockMvc. Без новых зависимостей.

## Global Constraints

- Ветка `feat/iteration-3-plants` (от `main`); Conventional Commits; тесты коммитируются вместе с реализацией или раньше; красные тесты в `main` не попадают.
- TDD (раздел 14): сначала красный приёмочный `PlantsApiIT` (HTTP + Testcontainers), затем внутренний цикл red→green→refactor (домен → application → адаптеры).
- `./mvnw verify` — единственная команда проверки; совокупный LINE coverage ≥ 70% (gate).
- Все ID — UUID; время — `Instant`/UTC из внедрённого `Clock`; домен получает `Instant now` аргументом, не `Instant.now()`.
- Enum — VARCHAR + CHECK в миграциях, JPA `EnumType.STRING`; Hibernate `ddl-auto=validate`; Flyway — единственный источник структуры.
- `version` (optimistic locking, JPA `@Version`) — у агрегата `Plant`; `ImageRestriction` — append-only, `PlantReservation` защищается частичным уникальным индексом (без `@Version`) — обосновать в ADR-008.
- Границы контекстов — только через `api` и только из адаптеров (ArchUnit `ContextBoundaryTest`, `LayerRulesTest` уже зелёные — не ослаблять правила). `plants.api` не зависит от `plants.domain`/`adapter`; `plants.application` реализует фасады `plants.api`.
- Межконтекстные ACID-отступления от «одна транзакция — один агрегат» (раздел 12) только для описанных в ADR-008 процессов (подача растения + claim media; гибель Plant + ImageRestriction; резервирование с проверками) — с планом на лабу №2.
- **Урок итерации 2:** абстрактные базовые классы контрактных тестов репозиториев обязаны нести `@Transactional` — иначе наследуемые тест-методы выполняются без транзакции (аннотация `@DataJpaTest` на подклассе не применяется к методам родителя) и протекают в общую БД Testcontainers.
- Новые понятия — сначала в `docs/domain/glossary.md`, изменения правил — сначала в docs (context map, aggregates, ADR), затем код (Task 7).
- Python в среде — 3.9.6 (без `X | None` в скриптах).
- Среда: macOS/zsh; рабочая директория `lab1-monolith/`; scratch-файлы диагностики не коммитировать.

## Карта файлов итерации

```text
src/main/java/com/plantarena/
├── shared/
│   ├── event/                        НОВОЕ: IntegrationEvent (маркер-конверт), IntegrationEventPublisher (порт)
│   └── web/ApiError.java             ИЗМЕНЕНО: +retryAt (nullable) для временных запретов (раздел 13)
├── config/EventWiringConfig.java     НОВОЕ: in-process IntegrationEventPublisher (ApplicationEventPublisher)
├── media/
│   ├── api/                          НОВОЕ: MediaAssets (чтение), MediaAssetClaims (команды), MediaAssetData
│   ├── domain/AssetClaim.java        НОВОЕ: задействованность asset (media-сторона)
│   ├── application/
│   │   ├── AssetClaimRepository.java НОВОЕ: порт out
│   │   ├── AssetInUseException.java  НОВОЕ: 409 ASSET_IN_USE
│   │   ├── MediaAssetsFacade.java    НОВОЕ: реализация media.api.MediaAssets
│   │   ├── MediaAssetClaimsFacade.java НОВОЕ: реализация media.api.MediaAssetClaims
│   │   ├── MediaAssetService.java    ИЗМЕНЕНО: delete проверяет claim; download учитывает публичность
│   │   └── MediaAccessPolicy.java    ИЗМЕНЕНО: requireViewer(actor, ownerId, publiclyVisible)
│   └── adapter/
│       ├── in/web/MediaExceptionHandler.java  ИЗМЕНЕНО: +ASSET_IN_USE 409
│       └── out/persistence/          НОВОЕ: AssetClaimJpaEntity, AssetClaimJpaRepository, JpaAssetClaimRepository
├── plants/
│   ├── api/                          НОВОЕ: PlantModeration, PlantEligibility, PlantLifecycle, PlantData,
│   │                                 enums, исключения; api/event/: PlantSubmittedEvent,
│   │                                 PlantModerationDecidedEvent, PlantDiedEvent
│   ├── domain/                       НОВОЕ: Plant, ImageFingerprint, ImageRestriction, PlantReservation,
│   │                                 ImageReusePolicy, enums, PlantAlreadyDecidedException,
│   │                                 PlantRepository, ImageRestrictionRepository, PlantReservationRepository
│   ├── application/
│   │   ├── port/in/                  SubmitPlantUseCase, ListPlantsUseCase, GetPlantUseCase,
│   │   │                             RenamePlantUseCase, ArchivePlantUseCase, GetPlantModerationUseCase
│   │   ├── port/out/                 MediaAssetsGateway, MediaAssetClaimsGateway
│   │   ├── PlantService.java, PlantModerationService.java, PlantEligibilityService.java,
│   │   │                             PlantLifecycleService.java, PlantsAccessPolicy.java
│   │   └── исключения: AssetNotFoundException, AssetAlreadyClaimedException,
│   │                                 ImageRestrictedException, PlantUnderReservationException
│   └── adapter/
│       ├── in/web/                   PlantController, SubmitPlantRequest, RenamePlantRequest,
│       │                             PlantResponse, PlantModerationResponse, PlantExceptionHandler
│       ├── out/media/InProcessMediaGateway.java   ACL к media.api (оба порта)
│       └── out/persistence/          JPA-сущности + Spring Data + Jpa*-репозитории (3 агрегата)
└── resources/db/migration/
    ├── plants/V2__plants.sql         plant, image_restriction, plant_reservation (+частичный уникальный индекс)
    └── media/V3__asset_claims.sql    asset_claim
```

Тесты: `PlantsApiIT` (приёмочный), доменные `*Test` (5), application `*ServiceTest` (4) + support-фейки (6), контрактные `*RepositoryContractTest` (3 абстрактных + in-memory наследники + JPA IT), media `MediaAssetClaimsFacadeTest` + `MediaAssetServiceTest` (изменён) + `JpaAssetClaimRepositoryIT`, `PlantExceptionHandlerTest`.

---

### Task 1: plants.api-скелет, shared.event, эталонный green-8x8.png, красный PlantsApiIT

**Files:**
- Create: `src/main/java/com/plantarena/shared/event/IntegrationEvent.java`
- Create: `src/main/java/com/plantarena/shared/event/IntegrationEventPublisher.java`
- Create: `src/main/java/com/plantarena/plants/api/PlantModeration.java`
- Create: `src/main/java/com/plantarena/plants/api/PlantEligibility.java`
- Create: `src/main/java/com/plantarena/plants/api/PlantLifecycle.java`
- Create: `src/main/java/com/plantarena/plants/api/PlantData.java`
- Create: `src/main/java/com/plantarena/plants/api/PlantModerationStatus.java`
- Create: `src/main/java/com/plantarena/plants/api/PlantLifeStatus.java`
- Create: `src/main/java/com/plantarena/plants/api/PlantNotFoundException.java`
- Create: `src/main/java/com/plantarena/plants/api/PlantNotEligibleException.java`
- Create: `src/main/java/com/plantarena/plants/api/ReservationConflictException.java`
- Create: `src/main/java/com/plantarena/plants/api/ModerationAlreadyDecidedException.java`
- Create: `src/main/java/com/plantarena/plants/api/event/PlantSubmittedEvent.java`
- Create: `src/main/java/com/plantarena/plants/api/event/PlantModerationDecidedEvent.java`
- Create: `src/main/java/com/plantarena/plants/api/event/PlantDiedEvent.java`
- Create: `src/test/resources/media/reference/green-8x8.png` (генерация скриптом)
- Test: `src/test/java/com/plantarena/plants/PlantsApiIT.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest` (Testcontainers, bootstrap-админ, media storage root), media REST `/api/v1/files` (итерация 2), `CurrentActor`/`X-Demo-User-Id` (ADR-005).
- Produces: опубликованный контракт `plants.api` (сигнатуры ниже — их реализуют Task 3 и используют moderation/tournaments в итерациях 4–7); события с конвертом (eventId, eventType, schemaVersion, aggregateId, aggregateVersion, occurredAt, correlationId, payload); красный приёмочный IT, который становится зелёным в Task 6.

- [ ] **Step 1: Эталонный green-8x8.png**

Для тестов «другой отпечаток» нужен второй эталонный файл с другими пикселями (8×8, RGB, без метаданных — как `red-8x8.png` из итерации 2). Генерация чистым Python (stdlib):

```bash
python3 - <<'EOF'
import struct, zlib, binascii

def chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data
            + struct.pack(">I", binascii.crc32(tag + data) & 0xffffffff))

width = height = 8
row = b"\x00" + b"\x00\x80\x00" * width          # filter byte + RGB(0,128,0) на пиксель
raw = row * height
png = (b"\x89PNG\r\n\x1a\n"
       + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
       + chunk(b"IDAT", zlib.compress(raw, 9))
       + chunk(b"IEND", b""))
path = "src/test/resources/media/reference/green-8x8.png"
open(path, "wb").write(png)
print("written", len(png), "bytes")
EOF
```

Ожидание: `written 71 bytes` (порядок). Проверить, что файл читается ImageIO, можно позже через IT (загрузка green проходит валидацию формата).

- [ ] **Step 2: shared.event — маркер-конверт и порт публикации**

`src/main/java/com/plantarena/shared/event/IntegrationEvent.java`:

```java
package com.plantarena.shared.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Маркер опубликованного события (раздел 5 требований): события для других
 * контекстов несут поля будущего конверта уже сейчас. Живёт в shared.event —
 * технический минимум, не доменное понятие (shared не зависит от контекстов).
 */
public interface IntegrationEvent {

    UUID eventId();

    String eventType();

    int schemaVersion();

    UUID aggregateId();

    long aggregateVersion();

    Instant occurredAt();

    UUID correlationId();
}
```

`src/main/java/com/plantarena/shared/event/IntegrationEventPublisher.java`:

```java
package com.plantarena.shared.event;

/**
 * Порт публикации опубликованных событий (раздел 10.3): application-слой
 * публикует через порт; in-process реализация на ApplicationEventPublisher
 * (config), подписчики — в adapter.in.events контекстов-потребителей.
 * В лабе №4 заменяется на transactional outbox + Kafka.
 */
public interface IntegrationEventPublisher {

    void publish(IntegrationEvent event);
}
```

- [ ] **Step 3: plants.api — фасады, DTO, исключения, события**

`src/main/java/com/plantarena/plants/api/PlantModerationStatus.java`:

```java
package com.plantarena.plants.api;

/** Опубликованный enum статуса модерации (api не зависит от домена, правило 10.2.4). */
public enum PlantModerationStatus {
    PENDING, APPROVED, REJECTED
}
```

`src/main/java/com/plantarena/plants/api/PlantLifeStatus.java`:

```java
package com.plantarena.plants.api;

/** Опубликованный enum статуса жизни (api не зависит от домена, правило 10.2.4). */
public enum PlantLifeStatus {
    ALIVE, DEAD
}
```

`src/main/java/com/plantarena/plants/api/PlantData.java`:

```java
package com.plantarena.plants.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованные данные растения (раздел 6): без fingerprint наружу —
 * отпечаток внутренний инструмент запретов, публично не нужен.
 */
public record PlantData(
        UUID id,
        UUID ownerId,
        UUID assetId,
        String title,
        PlantModerationStatus moderationStatus,
        PlantLifeStatus lifeStatus,
        Instant createdAt,
        Instant diedAt,
        Instant archivedAt) {
}
```

`src/main/java/com/plantarena/plants/api/PlantModeration.java`:

```java
package com.plantarena.plants.api;

import java.util.UUID;

/**
 * Опубликованный контракт plants для moderation (раздел 4.3): решение
 * передаётся командой, а не записью в таблицы plants. Реализация — в
 * plants.application (правило 10.2.4).
 */
public interface PlantModeration {

    /**
     * Зафиксировать решение модерации по заявке.
     * Идемпотентно: повтор того же решения — no-op; конфликтующее решение
     * по уже решённой заявке — {@link ModerationAlreadyDecidedException}.
     */
    PlantData recordDecision(UUID plantId, Decision decision, String reason);

    enum Decision {
        APPROVED, REJECTED
    }
}
```

`src/main/java/com/plantarena/plants/api/PlantEligibility.java`:

```java
package com.plantarena.plants.api;

import java.util.UUID;

/**
 * Опубликованный контракт plants для tournaments (раздел 6): две операции
 * допуска. reserveSubmission допускает статус PENDING/APPROVED (незавершённая
 * модерация разрешает подачу заявки, но не допуск к голосованию);
 * confirmEligibility допускает к старту только APPROVED и подтверждает
 * существующий резерв. Проверки и изменение резерва атомарны внутри plants.
 */
public interface PlantEligibility {

    /**
     * Зарезервировать изображение за заявкой. Проверяет владельца, ALIVE,
     * отсутствие запрета и допустимый статус; идемпотентен по ключу:
     * повтор с тем же ключом возвращает тот же reservationId.
     *
     * @throws PlantNotEligibleException растение не проходит проверки
     * @throws ReservationConflictException изображение уже активно зарезервировано
     */
    UUID reserveSubmission(UUID ownerId, UUID plantId, UUID idempotencyKey);

    /**
     * Подтвердить допуск к старту: только APPROVED и активный резерв
     * этой же заявки.
     *
     * @throws PlantNotEligibleException растение/резерв не проходят проверки
     */
    void confirmEligibility(UUID ownerId, UUID plantId, UUID reservationId);

    /**
     * Освободить резерв (отказ/отмена/завершение). Идемпотентно.
     */
    void releaseReservation(UUID reservationId);
}
```

`src/main/java/com/plantarena/plants/api/PlantLifecycle.java`:

```java
package com.plantarena.plants.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованный контракт plants для tournaments (раздел 4.3): гибель
 * передаётся командой, а не записью в таблицы plants. Идемпотентна:
 * повторная гибель уже погибшего растения — no-op (рестарт без повторной
 * гибели, раздел 8).
 */
public interface PlantLifecycle {

    /**
     * Зарегистрировать гибель растения и запрет его изображения.
     *
     * @param kind             вид запрета: PERMANENT (поражение в закрытом
     *                         турнире) или COOLDOWN (поражение в глобальном, 24 ч —
     *                         политика 24 ч принадлежит tournaments)
     * @param cooldownExpiresAt обязателен для COOLDOWN, запрещён для PERMANENT
     * @param reason           причина гибели (единый язык, для истории)
     * @param sourceEntryId    участие, из которого последовала гибель (может быть null)
     */
    void registerDeath(UUID plantId, RestrictionKind kind, Instant cooldownExpiresAt,
                       String reason, UUID sourceEntryId);

    enum RestrictionKind {
        PERMANENT, COOLDOWN
    }
}
```

`src/main/java/com/plantarena/plants/api/PlantNotFoundException.java`:

```java
package com.plantarena.plants.api;

/** Растение не найдено или скрыто политикой приватности (404, раздел 13). */
public final class PlantNotFoundException extends RuntimeException {

    public PlantNotFoundException(String message) {
        super(message);
    }
}
```

`src/main/java/com/plantarena/plants/api/PlantNotEligibleException.java`:

```java
package com.plantarena.plants.api;

import java.time.Instant;

/** Растение не проходит проверки допуска (контракт PlantEligibility, раздел 6). */
public final class PlantNotEligibleException extends RuntimeException {

    private final Reason reason;
    private final Instant restrictedUntil;

    public PlantNotEligibleException(Reason reason, String message) {
        this(reason, message, null);
    }

    public PlantNotEligibleException(Reason reason, String message, Instant restrictedUntil) {
        super(message);
        this.reason = reason;
        this.restrictedUntil = restrictedUntil;
    }

    public Reason reason() {
        return reason;
    }

    /** Для RESTRICTED: момент, до которого изображение запрещено (null для PERMANENT). */
    public Instant restrictedUntil() {
        return restrictedUntil;
    }

    public enum Reason {
        NOT_OWNER, DEAD, RESTRICTED, NOT_RESERVABLE, NOT_APPROVED, NO_ACTIVE_RESERVATION
    }
}
```

`src/main/java/com/plantarena/plants/api/ReservationConflictException.java`:

```java
package com.plantarena.plants.api;

/** Изображение уже активно зарезервировано другой заявкой (допущение 4 раздела 3). */
public final class ReservationConflictException extends RuntimeException {

    public ReservationConflictException(String message) {
        super(message);
    }
}
```

`src/main/java/com/plantarena/plants/api/ModerationAlreadyDecidedException.java`:

```java
package com.plantarena.plants.api;

/** По заявке уже зафиксировано другое решение; устаревший результат не применяется (раздел 6). */
public final class ModerationAlreadyDecidedException extends RuntimeException {

    public ModerationAlreadyDecidedException(String message) {
        super(message);
    }
}
```

`src/main/java/com/plantarena/plants/api/event/PlantSubmittedEvent.java`:

```java
package com.plantarena.plants.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованное событие: создана заявка «это моё растение» (раздел 4.3).
 * moderation подписан на него и создаёт задание распознавания (итерация 4).
 */
public record PlantSubmittedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "PlantSubmitted";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID plantId, UUID ownerId, UUID assetId,
                          String fingerprint, int fingerprintVersion) {
    }
}
```

`src/main/java/com/plantarena/plants/api/event/PlantModerationDecidedEvent.java`:

```java
package com.plantarena.plants.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованное событие: модерация решила судьбу заявки (раздел 4.3).
 * tournaments подписан и переводит заявку (READY / возврат в INVITED, итерация 5).
 */
public record PlantModerationDecidedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "PlantModerationDecided";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID plantId, UUID ownerId, String decision, String reason) {
    }
}
```

`src/main/java/com/plantarena/plants/api/event/PlantDiedEvent.java`:

```java
package com.plantarena.plants.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованное событие: растение погибло необратимо (раздел 4.3).
 * Потребители лабы №4: notification, tournament lifecycle.
 */
public record PlantDiedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "PlantDied";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID plantId, UUID ownerId, String fingerprint,
                          String restrictionKind, UUID sourceEntryId) {
    }
}
```

- [ ] **Step 4: Красный приёмочный PlantsApiIT**

`src/test/java/com/plantarena/plants/PlantsApiIT.java` (полный файл; компилируется против `plants.api` из Step 3, HTTP — против media из итерации 2; arrangement — через фасады `plants.api` и jdbcTemplate):

```java
package com.plantarena.plants;

import com.jayway.jsonpath.JsonPath;
import com.plantarena.plants.api.PlantEligibility;
import com.plantarena.plants.api.PlantLifecycle;
import com.plantarena.plants.api.PlantModeration;
import com.plantarena.plants.api.PlantNotEligibleException;
import com.plantarena.plants.api.ReservationConflictException;
import com.plantarena.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Внешний цикл TDD итерации 3: сценарии разделов 6 и 13 (растения) и допущения
 * 1–5 раздела 3 через HTTP на Testcontainers. Arrangement (решение модерации,
 * резерв, гибель) — через опубликованный контракт plants.api: приёмочный IT
 * легитимно импортирует несколько контекстов (ContextBoundaryTest исключает
 * тесты). Красный до Task 6.
 */
@DisplayName("Сценарии разделов 6 и 13 (растения): /plants, запреты, резервы, гибель")
class PlantsApiIT extends AbstractIntegrationTest {

    private static final String DEMO_HEADER = "X-Demo-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlantModeration plantModeration;

    @Autowired
    private PlantEligibility plantEligibility;

    @Autowired
    private PlantLifecycle plantLifecycle;

    @Test
    void пользователь_подает_растение_на_свой_файл() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-submit@example.com", "Plants Submit");
        UUID assetId = uploadAs(ownerId, "red-8x8.png");

        String response = mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Мой фикус"}
                    """.formatted(assetId)))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", containsString("/api/v1/plants/")))
            .andExpect(jsonPath("$.ownerId").value(ownerId.toString()))
            .andExpect(jsonPath("$.assetId").value(assetId.toString()))
            .andExpect(jsonPath("$.title").value("Мой фикус"))
            .andExpect(jsonPath("$.moderationStatus").value("PENDING"))
            .andExpect(jsonPath("$.lifeStatus").value("ALIVE"))
            .andExpect(jsonPath("$.createdAt").isNotEmpty())
            .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("fingerprint");
        assertThat(response).doesNotContain("storageKey");
    }

    @Test
    void гость_не_может_подать_растение() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-guest@example.com", "Plants Guest");
        UUID assetId = uploadAs(ownerId, "red-8x8.png");

        mockMvc.perform(post("/api/v1/plants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Чужой фикус"}
                    """.formatted(assetId)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("NOT_IDENTIFIED"));
    }

    @Test
    void чужой_файл_скрыт_при_подаче_растения() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-owner@example.com", "Plants Owner");
        UUID strangerId = createUserAsAdmin("plants-stranger@example.com", "Plants Stranger");
        UUID assetId = uploadAs(ownerId, "red-8x8.png");

        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, strangerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Не мой файл"}
                    """.formatted(assetId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
    }

    @Test
    void несуществующий_файл_не_подается() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-unknown-asset@example.com", "Plants Unknown");

        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"На пустоту"}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
    }

    @Test
    void файл_нельзя_задействовать_вторым_растением() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-reuse@example.com", "Plants Reuse");
        UUID assetId = uploadAs(ownerId, "red-8x8.png");
        submitPlantAs(ownerId, assetId, "Первое растение");

        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Второе растение на тот же файл"}
                    """.formatted(assetId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ASSET_ALREADY_CLAIMED"));
    }

    @Test
    void список_своих_растений_показывает_все_статусы() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-list@example.com", "Plants List");
        UUID first = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Первое");
        UUID second = submitPlantAs(ownerId, uploadAs(ownerId, "green-8x8.png"), "Второе");
        plantModeration.recordDecision(first, PlantModeration.Decision.APPROVED, null);

        mockMvc.perform(get("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total-Count", "2"))
            .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/v1/plants?ownerId=" + ownerId + "&page=0&size=1")
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total-Count", "2"))
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void чужой_список_раскрывает_только_одобренные_растения() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-list-owner@example.com", "Plants List Owner");
        UUID strangerId = createUserAsAdmin("plants-list-stranger@example.com", "Plants List Stranger");
        UUID pending = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Черновик");
        UUID approved = submitPlantAs(ownerId, uploadAs(ownerId, "green-8x8.png"), "Одобренное");
        UUID rejected = submitPlantAs(ownerId, uploadAs(ownerId, "green-8x8.png"), "Отклонённое");
        plantModeration.recordDecision(approved, PlantModeration.Decision.APPROVED, null);
        plantModeration.recordDecision(rejected, PlantModeration.Decision.REJECTED, "не растение");

        mockMvc.perform(get("/api/v1/plants?ownerId=" + ownerId)
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total-Count", "1"))
            .andExpect(jsonPath("$[0].id").value(approved.toString()))
            .andExpect(jsonPath("$[0].moderationStatus").value("APPROVED"));
    }

    @Test
    void чужое_растение_скрыто_до_одобрения() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-hidden@example.com", "Plants Hidden");
        UUID strangerId = createUserAsAdmin("plants-hidden-stranger@example.com", "Plants Hidden Stranger");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Черновик");

        mockMvc.perform(get("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.moderationStatus").value("PENDING"));
    }

    @Test
    void чужое_одобренное_растение_доступно_к_просмотру() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-public@example.com", "Plants Public");
        UUID strangerId = createUserAsAdmin("plants-public-stranger@example.com", "Plants Public Stranger");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Публичное");
        plantModeration.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);

        mockMvc.perform(get("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.moderationStatus").value("APPROVED"));
    }

    @Test
    void владелец_переименовывает_растение_только_по_названию() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-rename@example.com", "Plants Rename");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Старое имя");

        mockMvc.perform(patch("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"Новое имя","assetId":"%s","ownerId":"%s"}
                    """.formatted(UUID.randomUUID(), UUID.randomUUID())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Новое имя"))
            .andExpect(jsonPath("$.assetId").exists());

        mockMvc.perform(get("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(jsonPath("$.title").value("Новое имя"))
            .andExpect(jsonPath("$.lifeStatus").value("ALIVE"));
    }

    @Test
    void чужой_не_переименовывает_растение() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-rename-owner@example.com", "Plants Rename Owner");
        UUID strangerId = createUserAsAdmin("plants-rename-stranger@example.com", "Plants Rename Stranger");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Не трогай");
        plantModeration.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);

        mockMvc.perform(patch("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, strangerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"Взлом"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void владелец_архивирует_растение() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-archive@example.com", "Plants Archive");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "На архив");

        mockMvc.perform(delete("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isNotFound());
    }

    @Test
    void архивация_растения_под_активным_резервом_запрещена() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-reserved@example.com", "Plants Reserved");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "В турнире");
        UUID reservationId = plantEligibility.reserveSubmission(ownerId, plantId, UUID.randomUUID());

        mockMvc.perform(delete("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PLANT_UNDER_RESERVATION"));

        plantEligibility.releaseReservation(reservationId);

        mockMvc.perform(delete("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isNoContent());
    }

    @Test
    void статус_модерации_доступен_владельцу() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-moderation@example.com", "Plants Moderation");
        UUID strangerId = createUserAsAdmin("plants-moderation-stranger@example.com", "Plants Moderation Stranger");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "На модерации");

        mockMvc.perform(get("/api/v1/plants/" + plantId + "/moderation")
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.moderationStatus").value("PENDING"))
            .andExpect(jsonPath("$.retryUploadAllowed").value(false));

        plantModeration.recordDecision(plantId, PlantModeration.Decision.REJECTED, "не растение");

        mockMvc.perform(get("/api/v1/plants/" + plantId + "/moderation")
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.moderationStatus").value("REJECTED"))
            .andExpect(jsonPath("$.reason").value("не растение"))
            .andExpect(jsonPath("$.retryUploadAllowed").value(true));

        mockMvc.perform(get("/api/v1/plants/" + plantId + "/moderation")
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));
    }

    @Test
    void одобрение_раскрывает_файл_растения_другим_пользователям() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-file-public@example.com", "Plants File Public");
        UUID strangerId = createUserAsAdmin("plants-file-stranger@example.com", "Plants File Stranger");
        UUID assetId = uploadAs(ownerId, "red-8x8.png");
        UUID plantId = submitPlantAs(ownerId, assetId, "Заявленное");

        mockMvc.perform(get("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("MEDIA_ASSET_NOT_FOUND"));

        plantModeration.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);

        mockMvc.perform(get("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isOk());
    }

    @Test
    void удаление_файла_задействованного_растением_запрещено() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-file-delete@example.com", "Plants File Delete");
        UUID assetId = uploadAs(ownerId, "red-8x8.png");
        UUID plantId = submitPlantAs(ownerId, assetId, "Задействованное");

        mockMvc.perform(delete("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ASSET_IN_USE"));

        mockMvc.perform(delete("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isNoContent());
    }

    @Test
    void погибшее_растение_не_воскресает_повторная_загрузка_создаёт_новый_экземпляр() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-death@example.com", "Plants Death");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Первенец");
        plantModeration.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);
        plantLifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.PERMANENT, null,
            "поражение в закрытом турнире", UUID.randomUUID());

        mockMvc.perform(get("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lifeStatus").value("DEAD"))
            .andExpect(jsonPath("$.diedAt").isNotEmpty());

        UUID newPlantId = submitPlantAs(ownerId, uploadAs(ownerId, "green-8x8.png"), "Второй экземпляр");

        assertThat(newPlantId).isNotEqualTo(plantId);
        mockMvc.perform(get("/api/v1/plants/" + newPlantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(jsonPath("$.lifeStatus").value("ALIVE"));
    }

    @Test
    void постоянный_запрет_блокирует_ту_же_картинку_навсегда() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-permanent@example.com", "Plants Permanent");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Погибшее");
        plantLifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.PERMANENT, null,
            "поражение в закрытом турнире", UUID.randomUUID());

        UUID sameImageAsset = uploadAs(ownerId, "red-8x8.png"); // новые байты загрузятся — новый asset

        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Та же картинка"}
                    """.formatted(sameImageAsset)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IMAGE_RESTRICTED"))
            .andExpect(jsonPath("$.retryAt").isEmpty());
    }

    @Test
    void суточный_запрет_блокирует_совпавшую_картинку_до_истечения() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-cooldown@example.com", "Plants Cooldown");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Глобальное поражение");
        plantLifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.COOLDOWN,
            Instant.now().plus(Duration.ofHours(24)), "поражение в глобальном турнире", UUID.randomUUID());

        UUID sameImageAsset = uploadAs(ownerId, "red-8x8.png");

        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Та же картинка"}
                    """.formatted(sameImageAsset)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IMAGE_RESTRICTED"))
            .andExpect(jsonPath("$.retryAt").isNotEmpty());
    }

    @Test
    void постоянный_запрет_имеет_приоритет_над_временным() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-priority@example.com", "Plants Priority");
        UUID first = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Сначала глобальное");
        UUID second = submitPlantAs(ownerId, uploadAs(ownerId, "green-8x8.png"), "Потом закрытое");
        plantLifecycle.registerDeath(first, PlantLifecycle.RestrictionKind.COOLDOWN,
            Instant.now().plus(Duration.ofHours(24)), "поражение в глобальном турнире", UUID.randomUUID());
        plantLifecycle.registerDeath(second, PlantLifecycle.RestrictionKind.PERMANENT, null,
            "поражение в закрытом турнире", UUID.randomUUID());

        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Снова та же картинка"}
                    """.formatted(uploadAs(ownerId, "green-8x8.png"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IMAGE_RESTRICTED"))
            .andExpect(jsonPath("$.retryAt").isEmpty()); // действует постоянный запрет, не суточный
    }

    @Test
    void чужое_поражение_не_блокирует_фотографию_у_всех() throws Exception {
        UUID firstOwner = createUserAsAdmin("plants-restriction-a@example.com", "Plants Restriction A");
        UUID secondOwner = createUserAsAdmin("plants-restriction-b@example.com", "Plants Restriction B");
        UUID plantId = submitPlantAs(firstOwner, uploadAs(firstOwner, "red-8x8.png"), "Погибшее у A");
        plantLifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.PERMANENT, null,
            "поражение в закрытом турнире", UUID.randomUUID());

        UUID sameImageAsset = uploadAs(secondOwner, "red-8x8.png"); // те же пиксели, другой владелец

        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, secondOwner.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Та же картинка у другого владельца"}
                    """.formatted(sameImageAsset)))
            .andExpect(status().isCreated());
    }

    @Test
    void суточный_запрет_касается_только_совпавшей_картинки() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-other-image@example.com", "Plants Other Image");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Глобальное поражение");
        plantLifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.COOLDOWN,
            Instant.now().plus(Duration.ofHours(24)), "поражение в глобальном турнире", UUID.randomUUID());

        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Другая картинка сразу после гибели"}
                    """.formatted(uploadAs(ownerId, "green-8x8.png"))))
            .andExpect(status().isCreated());
    }

    @Test
    void резерв_изображения_идемпотентен_по_ключу_и_одиночен_на_пару() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-reserve@example.com", "Plants Reserve");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Для резерва");
        UUID key = UUID.randomUUID();

        UUID first = plantEligibility.reserveSubmission(ownerId, plantId, key);
        UUID retry = plantEligibility.reserveSubmission(ownerId, plantId, key);

        assertThat(retry).isEqualTo(first); // тот же ключ — тот же reservationId

        assertThatThrownBy(() -> plantEligibility.reserveSubmission(ownerId, plantId, UUID.randomUUID()))
            .isInstanceOf(ReservationConflictException.class); // другой ключ — конфликт (допущение 4)
    }

    @Test
    void подтверждение_допуска_требует_одобрения_и_подтверждает_резерв() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-confirm@example.com", "Plants Confirm");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "На допуск");
        UUID reservationId = plantEligibility.reserveSubmission(ownerId, plantId, UUID.randomUUID());

        assertThatThrownBy(() -> plantEligibility.confirmEligibility(ownerId, plantId, reservationId))
            .isInstanceOf(PlantNotEligibleException.class)
            .extracting("reason")
            .isEqualTo(PlantNotEligibleException.Reason.NOT_APPROVED); // PENDING не допускается к старту

        plantModeration.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);
        plantEligibility.confirmEligibility(ownerId, plantId, reservationId); // без исключения

        plantEligibility.releaseReservation(reservationId);
        UUID renewed = plantEligibility.reserveSubmission(ownerId, plantId, UUID.randomUUID());

        assertThat(renewed).isNotEqualTo(reservationId); // после освобождения — новый резерв
    }

    @Test
    void повторная_гибель_идемпотентна_и_не_дублирует_запрет() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-death-idem@example.com", "Plants Death Idem");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Одна гибель");
        String reason = "поражение в закрытом турнире";

        plantLifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.PERMANENT, null, reason, null);
        plantLifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.PERMANENT, null, reason, null);

        Integer restrictions = jdbcTemplate.queryForObject(
            "select count(*) from plants.image_restriction where owner_id = ?", Integer.class, ownerId);
        assertThat(restrictions).isEqualTo(1);
    }

    // ---------- helpers ----------

    private UUID createUserAsAdmin(String email, String displayName) throws Exception {
        String response = mockMvc.perform(post("/api/v1/users")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"password-9","displayName":"%s"}
                    """.formatted(email, displayName)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private UUID uploadAs(UUID userId, String referenceName) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("file", referenceName, MediaType.IMAGE_PNG_VALUE,
                    referenceBytes(referenceName)))
                .header(DEMO_HEADER, userId.toString()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private UUID submitPlantAs(UUID userId, UUID assetId, String title) throws Exception {
        String response = mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"%s"}
                    """.formatted(assetId, title)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private byte[] referenceBytes(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/media/reference/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Эталонный файл не найден: " + name);
            }
            return in.readAllBytes();
        }
    }

    private UUID adminId() {
        return jdbcTemplate.queryForObject(
            "select id from identity.app_user where email_normalized = ?",
            UUID.class, "admin@plantarena.local");
    }
}
```

- [ ] **Step 5: Прогнать — убедиться в красном**

```bash
./mvnw verify -Dit.test='PlantsApiIT' -DskipTests=false > /tmp/it3-red.log 2>&1; echo "exit: $?"
grep -c "ERROR" /tmp/it3-red.log
```

Ожидание: **красный** — все тесты PlantsApiIT с ошибками `UnsatisfiedDependencyException` (нет бинов `PlantModeration`/`PlantEligibility`/`PlantLifecycle` — реализации появятся в Task 3) либо 404/406 по эндпоинтам `/api/v1/plants`. Юнит-тесты и остальные IT не запускаются (`-Dit.test` фильтр); сборка BUILD FAILURE — это ожидаемый красный этап.

- [ ] **Step 6: Коммит**

```bash
git add src/main/java/com/plantarena/shared/event \
  src/main/java/com/plantarena/plants/api \
  src/test/resources/media/reference/green-8x8.png \
  src/test/java/com/plantarena/plants/PlantsApiIT.java
git commit -m "test(plants): красный приёмочный PlantsApiIT, контракт plants.api, shared.event, эталонный green-8x8.png"
```

---

### Task 2: Домен plants — Plant, ImageFingerprint, ImageRestriction, PlantReservation, ImageReusePolicy, порты

**Files:**
- Create: `src/main/java/com/plantarena/plants/domain/ModerationStatus.java`
- Create: `src/main/java/com/plantarena/plants/domain/LifeStatus.java`
- Create: `src/main/java/com/plantarena/plants/domain/RestrictionKind.java`
- Create: `src/main/java/com/plantarena/plants/domain/ReservationStatus.java`
- Create: `src/main/java/com/plantarena/plants/domain/ImageFingerprint.java`
- Create: `src/main/java/com/plantarena/plants/domain/Plant.java`
- Create: `src/main/java/com/plantarena/plants/domain/PlantAlreadyDecidedException.java`
- Create: `src/main/java/com/plantarena/plants/domain/ImageRestriction.java`
- Create: `src/main/java/com/plantarena/plants/domain/PlantReservation.java`
- Create: `src/main/java/com/plantarena/plants/domain/ImageReusePolicy.java`
- Create: `src/main/java/com/plantarena/plants/domain/PlantRepository.java`
- Create: `src/main/java/com/plantarena/plants/domain/ImageRestrictionRepository.java`
- Create: `src/main/java/com/plantarena/plants/domain/PlantReservationRepository.java`
- Test: `src/test/java/com/plantarena/plants/domain/PlantTest.java`
- Test: `src/test/java/com/plantarena/plants/domain/ImageFingerprintTest.java`
- Test: `src/test/java/com/plantarena/plants/domain/ImageRestrictionTest.java`
- Test: `src/test/java/com/plantarena/plants/domain/PlantReservationTest.java`
- Test: `src/test/java/com/plantarena/plants/domain/ImageReusePolicyTest.java`

**Interfaces:**
- Consumes: `plants.api` не используется (домен изолирован, правило 10.2.4); `Instant now` аргументом; `ImageFingerprint` media НЕ импортируется (свой VO с теми же правилами).
- Produces (для Task 3/5): `Plant.submit(ownerId, assetId, fingerprint, title, now)`, `Plant.applyDecision(ModerationStatus, String) → boolean`, `Plant.rename(String)`, `Plant.archive(Instant) → boolean`, `Plant.die(Instant) → boolean`, `Plant.restore(...)`; `ImageRestriction.permanent/cooldown(...)`; `PlantReservation.reserve(...)`, `release(Instant) → boolean`; `ImageReusePolicy.activeRestriction(List<ImageRestriction>, Instant) → Optional<ImageRestriction>`; порты `PlantRepository`, `ImageRestrictionRepository`, `PlantReservationRepository` (сигнатуры ниже).

- [ ] **Step 1: Enum'ы и VO**

`src/main/java/com/plantarena/plants/domain/ModerationStatus.java`:

```java
package com.plantarena.plants.domain;

/** Статус модерации заявки (раздел 6): PENDING → APPROVED / REJECTED. */
public enum ModerationStatus {
    PENDING, APPROVED, REJECTED
}
```

`src/main/java/com/plantarena/plants/domain/LifeStatus.java`:

```java
package com.plantarena.plants.domain;

/** Статус жизни (раздел 6): DEAD необратим (допущение 1 раздела 3). */
public enum LifeStatus {
    ALIVE, DEAD
}
```

`src/main/java/com/plantarena/plants/domain/RestrictionKind.java`:

```java
package com.plantarena.plants.domain;

/** Вид запрета изображения (раздел 6): PERMANENT без expiresAt; COOLDOWN активен при now &lt; expiresAt. */
public enum RestrictionKind {
    PERMANENT, COOLDOWN
}
```

`src/main/java/com/plantarena/plants/domain/ReservationStatus.java`:

```java
package com.plantarena.plants.domain;

/** Статус резерва изображения (раздел 6): ACTIVE → RELEASED. */
public enum ReservationStatus {
    ACTIVE, RELEASED
}
```

`src/main/java/com/plantarena/plants/domain/ImageFingerprint.java`:

```java
package com.plantarena.plants.domain;

/**
 * VO отпечатка изображения внутри plants (свой, не media: домены контекстов
 * не импортируют друг друга). Значение — 64 hex-символа (SHA-256, ADR-007),
 * версия алгоритма ≥ 1: запреты ищутся по паре (ownerId, fingerprint) с учётом
 * версии (ADR-007).
 */
public record ImageFingerprint(String value, int algorithmVersion) {

    private static final String HEX_64 = "[0-9a-f]{64}";

    public ImageFingerprint {
        if (value == null || !value.matches(HEX_64)) {
            throw new IllegalArgumentException(
                "Отпечаток должен быть 64 hex-символа: " + value);
        }
        if (algorithmVersion < 1) {
            throw new IllegalArgumentException("Версия алгоритма должна быть ≥ 1");
        }
    }
}
```

- [ ] **Step 2: Агрегат Plant + доменное исключение**

`src/main/java/com/plantarena/plants/domain/PlantAlreadyDecidedException.java`:

```java
package com.plantarena.plants.domain;

/**
 * По заявке уже зафиксировано другое решение: устаревший результат модерации
 * не применяется (раздел 6). Доменное исключение; в api-контракт переводит
 * application (PlantModerationService).
 */
public final class PlantAlreadyDecidedException extends RuntimeException {

    public PlantAlreadyDecidedException(String message) {
        super(message);
    }
}
```

`src/main/java/com/plantarena/plants/domain/Plant.java`:

```java
package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат plants (раздел 6): заявка «это моё растение» на конкретном изображении.
 * Инварианты: asset и отпечаток неизменяемы после подачи (мутаторов нет);
 * переходы модерации PENDING → APPROVED/REJECTED однократны; DEAD необратим;
 * архивация скрывает растение, сохраняя запреты и историю (раздел 13).
 * Время приходит аргументом (Instant now), не Instant.now().
 */
public final class Plant {

    private static final int TITLE_MAX = 100;

    private final UUID id;
    private final UUID ownerId;
    private final UUID assetId;
    private final ImageFingerprint fingerprint;
    private String title;
    private ModerationStatus moderationStatus;
    private String moderationReason;
    private LifeStatus lifeStatus;
    private final Instant createdAt;
    private Instant diedAt;
    private Instant archivedAt;
    private long version;

    private Plant(UUID id, UUID ownerId, UUID assetId, ImageFingerprint fingerprint, String title,
                  ModerationStatus moderationStatus, String moderationReason, LifeStatus lifeStatus,
                  Instant createdAt, Instant diedAt, Instant archivedAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.assetId = Objects.requireNonNull(assetId, "assetId");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.title = requireTitle(title);
        this.moderationStatus = Objects.requireNonNull(moderationStatus, "moderationStatus");
        this.moderationReason = moderationReason;
        this.lifeStatus = Objects.requireNonNull(lifeStatus, "lifeStatus");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (lifeStatus == LifeStatus.DEAD && diedAt == null) {
            throw new IllegalArgumentException("DEAD требует diedAt");
        }
        this.diedAt = diedAt;
        this.archivedAt = archivedAt;
        this.version = version;
    }

    /** Подача заявки: новый Plant всегда PENDING/ALIVE (раздел 6). */
    public static Plant submit(UUID ownerId, UUID assetId, ImageFingerprint fingerprint,
                               String title, Instant now) {
        return new Plant(UUID.randomUUID(), ownerId, assetId, fingerprint, title,
            ModerationStatus.PENDING, null, LifeStatus.ALIVE, now, null, null, 0);
    }

    /** Восстановление из хранилища с сохранением id и version (JPA-адаптер). */
    public static Plant restore(UUID id, UUID ownerId, UUID assetId, ImageFingerprint fingerprint,
                                String title, ModerationStatus moderationStatus, String moderationReason,
                                LifeStatus lifeStatus, Instant createdAt, Instant diedAt,
                                Instant archivedAt, long version) {
        return new Plant(id, ownerId, assetId, fingerprint, title, moderationStatus,
            moderationReason, lifeStatus, createdAt, diedAt, archivedAt, version);
    }

    /**
     * Решение модерации. Идемпотентно для того же решения (повтор доставки);
     * конфликтующее решение по решённой заявке — ошибка (устаревший результат
     * не применяется, раздел 6).
     *
     * @return true, если состояние изменилось
     */
    public boolean applyDecision(ModerationStatus decision, String reason) {
        if (moderationStatus == decision) {
            return false;
        }
        if (moderationStatus != ModerationStatus.PENDING) {
            throw new PlantAlreadyDecidedException(
                "Решение по заявке уже зафиксировано: " + moderationStatus);
        }
        this.moderationStatus = decision;
        this.moderationReason = reason;
        return true;
    }

    /** Переименование: только title, asset/owner/status недоступны (раздел 13). */
    public void rename(String newTitle) {
        this.title = requireTitle(newTitle);
    }

    /** Архивация: скрытие с сохранением истории; идемпотентна. */
    public boolean archive(Instant now) {
        if (archivedAt != null) {
            return false;
        }
        this.archivedAt = now;
        return true;
    }

    /** Гибель: ALIVE → DEAD необратим; идемпотентна для повторов доставки. */
    public boolean die(Instant now) {
        if (lifeStatus == LifeStatus.DEAD) {
            return false;
        }
        this.lifeStatus = LifeStatus.DEAD;
        this.diedAt = now;
        return true;
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public UUID assetId() {
        return assetId;
    }

    public ImageFingerprint fingerprint() {
        return fingerprint;
    }

    public String title() {
        return title;
    }

    public ModerationStatus moderationStatus() {
        return moderationStatus;
    }

    public String moderationReason() {
        return moderationReason;
    }

    public LifeStatus lifeStatus() {
        return lifeStatus;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant diedAt() {
        return diedAt;
    }

    public Instant archivedAt() {
        return archivedAt;
    }

    public long version() {
        return version;
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank() || title.trim().length() > TITLE_MAX) {
            throw new IllegalArgumentException(
                "Название растения: непустое, до " + TITLE_MAX + " символов");
        }
        return title.trim();
    }
}
```

- [ ] **Step 3: ImageRestriction и PlantReservation**

`src/main/java/com/plantarena/plants/domain/ImageRestriction.java`:

```java
package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат plants: запрет изображения для пары (ownerId, fingerprint)
 * (раздел 6, допущения 2–3). Append-only: история гибели не удаляется;
 * активность вычисляет ImageReusePolicy, а не статус. PERMANENT без expiresAt;
 * COOLDOWN блокирует, пока now &lt; expiresAt.
 */
public final class ImageRestriction {

    private final UUID id;
    private final UUID ownerId;
    private final ImageFingerprint fingerprint;
    private final RestrictionKind kind;
    private final Instant expiresAt;
    private final String reason;
    private final UUID sourceEntryId;
    private final Instant createdAt;

    private ImageRestriction(UUID id, UUID ownerId, ImageFingerprint fingerprint, RestrictionKind kind,
                             Instant expiresAt, String reason, UUID sourceEntryId, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.expiresAt = expiresAt;
        this.reason = requireReason(reason);
        this.sourceEntryId = sourceEntryId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (kind == RestrictionKind.PERMANENT && expiresAt != null) {
            throw new IllegalArgumentException("PERMANENT-запрет не имеет expiresAt");
        }
        if (kind == RestrictionKind.COOLDOWN && expiresAt == null) {
            throw new IllegalArgumentException("COOLDOWN-запрет требует expiresAt");
        }
    }

    /** Запрет навсегда (поражение в закрытом турнире, допущение 2). */
    public static ImageRestriction permanent(UUID ownerId, ImageFingerprint fingerprint,
                                             String reason, UUID sourceEntryId, Instant now) {
        return new ImageRestriction(UUID.randomUUID(), ownerId, fingerprint,
            RestrictionKind.PERMANENT, null, reason, sourceEntryId, now);
    }

    /** Временный запрет (поражение в глобальном турнире — 24 ч, допущение 2). */
    public static ImageRestriction cooldown(UUID ownerId, ImageFingerprint fingerprint,
                                            String reason, UUID sourceEntryId,
                                            Instant expiresAt, Instant now) {
        return new ImageRestriction(UUID.randomUUID(), ownerId, fingerprint,
            RestrictionKind.COOLDOWN, Objects.requireNonNull(expiresAt, "expiresAt"),
            reason, sourceEntryId, now);
    }

    /** Восстановление из хранилища (JPA-адаптер). */
    public static ImageRestriction restore(UUID id, UUID ownerId, ImageFingerprint fingerprint,
                                           RestrictionKind kind, Instant expiresAt, String reason,
                                           UUID sourceEntryId, Instant createdAt) {
        return new ImageRestriction(id, ownerId, fingerprint, kind, expiresAt,
            reason, sourceEntryId, createdAt);
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public ImageFingerprint fingerprint() {
        return fingerprint;
    }

    public RestrictionKind kind() {
        return kind;
    }

    /** null для PERMANENT; для COOLDOWN — момент окончания. */
    public Instant expiresAt() {
        return expiresAt;
    }

    public String reason() {
        return reason;
    }

    public UUID sourceEntryId() {
        return sourceEntryId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Причина запрета обязательна (для истории)");
        }
        return reason.trim();
    }
}
```

`src/main/java/com/plantarena/plants/domain/PlantReservation.java`:

```java
package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат plants: резерв пары (ownerId, fingerprint) за одной заявкой
 * (раздел 6, допущение 4). Set-инвариант «не более одного активного резерва
 * на пару» домен лишь формулирует — обеспечивает частичный уникальный индекс
 * PostgreSQL (раздел 5). Идемпотентность повтора — по idempotency key
 * (ID команды): повтор с тем же ключом возвращает тот же reservationId.
 */
public final class PlantReservation {

    private final UUID id;
    private final UUID ownerId;
    private final UUID plantId;
    private final ImageFingerprint fingerprint;
    private final UUID idempotencyKey;
    private ReservationStatus status;
    private final Instant createdAt;
    private Instant releasedAt;

    private PlantReservation(UUID id, UUID ownerId, UUID plantId, ImageFingerprint fingerprint,
                             UUID idempotencyKey, ReservationStatus status,
                             Instant createdAt, Instant releasedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.plantId = Objects.requireNonNull(plantId, "plantId");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (status == ReservationStatus.RELEASED && releasedAt == null) {
            throw new IllegalArgumentException("RELEASED требует releasedAt");
        }
        this.releasedAt = releasedAt;
    }

    public static PlantReservation reserve(UUID ownerId, UUID plantId, ImageFingerprint fingerprint,
                                           UUID idempotencyKey, Instant now) {
        return new PlantReservation(UUID.randomUUID(), ownerId, plantId, fingerprint,
            idempotencyKey, ReservationStatus.ACTIVE, now, null);
    }

    /** Восстановление из хранилища (JPA-адаптер). */
    public static PlantReservation restore(UUID id, UUID ownerId, UUID plantId,
                                           ImageFingerprint fingerprint, UUID idempotencyKey,
                                           ReservationStatus status, Instant createdAt,
                                           Instant releasedAt) {
        return new PlantReservation(id, ownerId, plantId, fingerprint, idempotencyKey,
            status, createdAt, releasedAt);
    }

    /** Освобождение (отказ/отмена/завершение); идемпотентно. */
    public boolean release(Instant now) {
        if (status == ReservationStatus.RELEASED) {
            return false;
        }
        this.status = ReservationStatus.RELEASED;
        this.releasedAt = now;
        return true;
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public UUID plantId() {
        return plantId;
    }

    public ImageFingerprint fingerprint() {
        return fingerprint;
    }

    public UUID idempotencyKey() {
        return idempotencyKey;
    }

    public ReservationStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant releasedAt() {
        return releasedAt;
    }
}
```

- [ ] **Step 4: ImageReusePolicy + порты репозиториев**

`src/main/java/com/plantarena/plants/domain/ImageReusePolicy.java`:

```java
package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Доменная политика повторного использования изображения (раздел 6,
 * допущения 2–3): постоянный запрет имеет приоритет над временным;
 * из нескольких COOLDOWN действует самый поздний expiresAt; истёкший
 * COOLDOWN не блокирует. Чистая функция без состояния.
 */
public final class ImageReusePolicy {

    /** Действующий активный запрет для пары (ownerId, fingerprint) или empty. */
    public Optional<ImageRestriction> activeRestriction(List<ImageRestriction> restrictions,
                                                        Instant now) {
        Optional<ImageRestriction> permanent = restrictions.stream()
            .filter(restriction -> restriction.kind() == RestrictionKind.PERMANENT)
            .findFirst();
        if (permanent.isPresent()) {
            return permanent;
        }
        return restrictions.stream()
            .filter(restriction -> restriction.kind() == RestrictionKind.COOLDOWN)
            .filter(restriction -> now.isBefore(restriction.expiresAt()))
            .max(Comparator.comparing(ImageRestriction::expiresAt));
    }
}
```

`src/main/java/com/plantarena/plants/domain/PlantRepository.java`:

```java
package com.plantarena.plants.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Порт репозитория агрегата Plant (раздел 5): интерфейс в domain,
 * реализация в adapter.out.persistence. Список отсортирован по id —
 * детерминированная пагинация с tie-break (раздел 13).
 */
public interface PlantRepository {

    Plant save(Plant plant);

    Optional<Plant> findById(UUID id);

    /** Неархивированное растение на asset: один живой Plant на файл (ADR-008). */
    Optional<Plant> findActiveByAssetId(UUID assetId);

    /** Все неархивированные растения владельца (владельцу и админу). */
    List<Plant> findByOwner(UUID ownerId, int offset, int limit);

    long countByOwner(UUID ownerId);

    /** Публичные для остальных: APPROVED и не архивированы (ADR-008). */
    List<Plant> findApprovedByOwner(UUID ownerId, int offset, int limit);

    long countApprovedByOwner(UUID ownerId);
}
```

`src/main/java/com/plantarena/plants/domain/ImageRestrictionRepository.java`:

```java
package com.plantarena.plants.domain;

import java.util.List;
import java.util.UUID;

/** Порт репозитория запретов: append-only, поиск по паре (ownerId, fingerprint). */
public interface ImageRestrictionRepository {

    ImageRestriction save(ImageRestriction restriction);

    List<ImageRestriction> findByOwnerAndFingerprint(UUID ownerId, String fingerprintValue);
}
```

`src/main/java/com/plantarena/plants/domain/PlantReservationRepository.java`:

```java
package com.plantarena.plants.domain;

import java.util.Optional;
import java.util.UUID;

/** Порт репозитория резервов изображений (раздел 6). */
public interface PlantReservationRepository {

    PlantReservation save(PlantReservation reservation);

    Optional<PlantReservation> findById(UUID id);

    /** Идемпотентный повтор команды резервирования (по ключу команды). */
    Optional<PlantReservation> findByIdempotencyKey(UUID idempotencyKey);

    /** Активный резерв пары (ownerId, fingerprint) — не более одного (допущение 4). */
    Optional<PlantReservation> findActiveByOwnerAndFingerprint(UUID ownerId, String fingerprintValue);

    /** Активный резерв конкретной заявки (для запрета архивации, раздел 13). */
    Optional<PlantReservation> findActiveByPlantId(UUID plantId);
}
```

- [ ] **Step 5: Доменные тесты (red → green)**

`src/test/java/com/plantarena/plants/domain/ImageFingerprintTest.java`:

```java
package com.plantarena.plants.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VO ImageFingerprint (plants): 64 hex + версия алгоритма")
class ImageFingerprintTest {

    private static final String VALID = "a".repeat(64);

    @Test
    void корректный_отпечаток_создаётся() {
        ImageFingerprint fingerprint = new ImageFingerprint(VALID, 1);

        assertThat(fingerprint.value()).isEqualTo(VALID);
        assertThat(fingerprint.algorithmVersion()).isEqualTo(1);
    }

    @Test
    void некорректное_значение_невозможно_создать() {
        assertThatThrownBy(() -> new ImageFingerprint(null, 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageFingerprint("ABC", 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageFingerprint("Z".repeat(64), 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageFingerprint("a".repeat(63), 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void версия_алгоритма_положительна() {
        assertThatThrownBy(() -> new ImageFingerprint(VALID, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`src/test/java/com/plantarena/plants/domain/PlantTest.java`:

```java
package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Агрегат Plant: переходы модерации и жизни, неизменяемость asset")
class PlantTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final ImageFingerprint FINGERPRINT =
        new ImageFingerprint("b".repeat(64), 1);

    private Plant newPlant() {
        return Plant.submit(UUID.randomUUID(), UUID.randomUUID(), FINGERPRINT, "Фикус", NOW);
    }

    @Test
    void подача_создаёт_PENDING_ALIVE_растение() {
        Plant plant = newPlant();

        assertThat(plant.moderationStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(plant.lifeStatus()).isEqualTo(LifeStatus.ALIVE);
        assertThat(plant.createdAt()).isEqualTo(NOW);
        assertThat(plant.diedAt()).isNull();
        assertThat(plant.archivedAt()).isNull();
        assertThat(plant.moderationReason()).isNull();
    }

    @Test
    void название_валидируется_доменом() {
        assertThatThrownBy(() -> Plant.submit(UUID.randomUUID(), UUID.randomUUID(),
            FINGERPRINT, "   ", NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Plant.submit(UUID.randomUUID(), UUID.randomUUID(),
            FINGERPRINT, "д".repeat(101), NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void одобрение_и_отклонение_переводят_из_PENDING() {
        Plant approved = newPlant();
        assertThat(approved.applyDecision(ModerationStatus.APPROVED, null)).isTrue();
        assertThat(approved.moderationStatus()).isEqualTo(ModerationStatus.APPROVED);

        Plant rejected = newPlant();
        assertThat(rejected.applyDecision(ModerationStatus.REJECTED, "не растение")).isTrue();
        assertThat(rejected.moderationReason()).isEqualTo("не растение");
    }

    @Test
    void повтор_того_же_решения_идемпотентен() {
        Plant plant = newPlant();
        plant.applyDecision(ModerationStatus.APPROVED, null);

        assertThat(plant.applyDecision(ModerationStatus.APPROVED, null)).isFalse();
        assertThat(plant.moderationStatus()).isEqualTo(ModerationStatus.APPROVED);
    }

    @Test
    void конфликтующее_решение_по_решённой_заявке_отклоняется() {
        Plant plant = newPlant();
        plant.applyDecision(ModerationStatus.APPROVED, null);

        assertThatThrownBy(() -> plant.applyDecision(ModerationStatus.REJECTED, "опоздало"))
            .isInstanceOf(PlantAlreadyDecidedException.class);
    }

    @Test
    void гибель_необратима_и_фиксирует_момент() {
        Plant plant = newPlant();

        assertThat(plant.die(NOW.plusSeconds(60))).isTrue();
        assertThat(plant.lifeStatus()).isEqualTo(LifeStatus.DEAD);
        assertThat(plant.diedAt()).isEqualTo(NOW.plusSeconds(60));

        assertThat(plant.die(NOW.plusSeconds(120))).isFalse(); // повтор — no-op
        assertThat(plant.diedAt()).isEqualTo(NOW.plusSeconds(60)); // момент не смещается
    }

    @Test
    void архивация_скрывает_и_идемпотентна() {
        Plant plant = newPlant();

        assertThat(plant.archive(NOW.plusSeconds(30))).isTrue();
        assertThat(plant.archivedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(plant.archive(NOW.plusSeconds(60))).isFalse();
    }

    @Test
    void переименование_меняет_только_название() {
        Plant plant = newPlant();
        UUID assetIdBefore = plant.assetId();
        UUID ownerBefore = plant.ownerId();

        plant.rename("  Новое имя  ");

        assertThat(plant.title()).isEqualTo("Новое имя");
        assertThat(plant.assetId()).isEqualTo(assetIdBefore); // asset не меняется после подачи
        assertThat(plant.ownerId()).isEqualTo(ownerBefore);
        assertThat(plant.moderationStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(plant.lifeStatus()).isEqualTo(LifeStatus.ALIVE);
    }

    @Test
    void восстановление_требует_diedAt_для_DEAD() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> Plant.restore(id, UUID.randomUUID(), UUID.randomUUID(),
            FINGERPRINT, "Фикус", ModerationStatus.PENDING, null, LifeStatus.DEAD,
            NOW, null, null, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`src/test/java/com/plantarena/plants/domain/ImageRestrictionTest.java`:

```java
package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Агрегат ImageRestriction: PERMANENT без expiresAt, COOLDOWN с expiresAt")
class ImageRestrictionTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final ImageFingerprint FINGERPRINT =
        new ImageFingerprint("c".repeat(64), 1);

    @Test
    void постоянный_запрет_без_истечения() {
        ImageRestriction restriction =
            ImageRestriction.permanent(UUID.randomUUID(), FINGERPRINT,
                "поражение в закрытом турнире", UUID.randomUUID(), NOW);

        assertThat(restriction.kind()).isEqualTo(RestrictionKind.PERMANENT);
        assertThat(restriction.expiresAt()).isNull();
    }

    @Test
    void суточный_запрет_с_истечением() {
        ImageRestriction restriction =
            ImageRestriction.cooldown(UUID.randomUUID(), FINGERPRINT,
                "поражение в глобальном турнире", UUID.randomUUID(),
                NOW.plusSeconds(86400), NOW);

        assertThat(restriction.kind()).isEqualTo(RestrictionKind.COOLDOWN);
        assertThat(restriction.expiresAt()).isEqualTo(NOW.plusSeconds(86400));
    }

    @Test
    void причина_обязательна_для_истории() {
        assertThatThrownBy(() -> ImageRestriction.permanent(UUID.randomUUID(), FINGERPRINT,
            "  ", null, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`src/test/java/com/plantarena/plants/domain/PlantReservationTest.java`:

```java
package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Агрегат PlantReservation: ACTIVE → RELEASED, идемпотентное освобождение")
class PlantReservationTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    private PlantReservation newReservation() {
        return PlantReservation.reserve(UUID.randomUUID(), UUID.randomUUID(),
            new ImageFingerprint("d".repeat(64), 1), UUID.randomUUID(), NOW);
    }

    @Test
    void резерв_создаётся_активным_с_ключом_команды() {
        UUID key = UUID.randomUUID();
        PlantReservation reservation = PlantReservation.reserve(
            UUID.randomUUID(), UUID.randomUUID(),
            new ImageFingerprint("d".repeat(64), 1), key, NOW);

        assertThat(reservation.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(reservation.idempotencyKey()).isEqualTo(key);
        assertThat(reservation.releasedAt()).isNull();
    }

    @Test
    void освобождение_фиксирует_момент_и_идемпотентно() {
        PlantReservation reservation = newReservation();

        assertThat(reservation.release(NOW.plusSeconds(60))).isTrue();
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservation.releasedAt()).isEqualTo(NOW.plusSeconds(60));

        assertThat(reservation.release(NOW.plusSeconds(120))).isFalse();
        assertThat(reservation.releasedAt()).isEqualTo(NOW.plusSeconds(60));
    }
}
```

`src/test/java/com/plantarena/plants/domain/ImageReusePolicyTest.java`:

```java
package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Политика повторного использования: постоянный запрет приоритетнее временного")
class ImageReusePolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final ImageFingerprint FINGERPRINT =
        new ImageFingerprint("e".repeat(64), 1);
    private final ImageReusePolicy policy = new ImageReusePolicy();

    private ImageRestriction permanent() {
        return ImageRestriction.permanent(UUID.randomUUID(), FINGERPRINT,
            "закрытый турнир", null, NOW.minusSeconds(1));
    }

    private ImageRestriction cooldown(Instant expiresAt) {
        return ImageRestriction.cooldown(UUID.randomUUID(), FINGERPRINT,
            "глобальный турнир", null, expiresAt, NOW.minusSeconds(1));
    }

    @Test
    void без_запретов_повторное_использование_разрешено() {
        assertThat(policy.activeRestriction(List.of(), NOW)).isEmpty();
    }

    @Test
    void постоянный_запрет_действует_всегда() {
        assertThat(policy.activeRestriction(List.of(permanent()), NOW.plusSeconds(10_000_000)))
            .isPresent()
            .get()
            .extracting(ImageRestriction::kind)
            .isEqualTo(RestrictionKind.PERMANENT);
    }

    @Test
    void суточный_запрет_действует_до_истечения() {
        assertThat(policy.activeRestriction(List.of(cooldown(NOW.plusSeconds(3600))), NOW))
            .isPresent();
        assertThat(policy.activeRestriction(List.of(cooldown(NOW.plusSeconds(3600))),
            NOW.plusSeconds(3600)))
            .isEmpty(); // now >= expiresAt — разрешено (полуоткрытый интервал)
    }

    @Test
    void постоянный_запрет_приоритетнее_временного() {
        ImageRestriction result = policy.activeRestriction(
            List.of(cooldown(NOW.plusSeconds(3600)), permanent()), NOW).orElseThrow();

        assertThat(result.kind()).isEqualTo(RestrictionKind.PERMANENT);
    }

    @Test
    void из_нескольких_временных_действует_самый_поздний() {
        ImageRestriction result = policy.activeRestriction(
            List.of(cooldown(NOW.plusSeconds(600)), cooldown(NOW.plusSeconds(3600))), NOW)
            .orElseThrow();

        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }
}
```

- [ ] **Step 6: Прогнать доменные тесты**

```bash
./mvnw test -Dtest='com.plantarena.plants.domain.*' > /tmp/it3-domain.log 2>&1; echo "exit: $?"
grep -E "Tests run" /tmp/it3-domain.log | tail -1
```

Ожидание: BUILD SUCCESS, 5 тестовых классов зелёные (24 теста). ArchUnit остаётся зелёным (домен ни от чего не зависит).

- [ ] **Step 7: Коммит**

```bash
git add src/main/java/com/plantarena/plants/domain src/test/java/com/plantarena/plants/domain
git commit -m "feat(plants): домен — Plant, ImageRestriction, PlantReservation, ImageReusePolicy, порты"
```

---

### Task 3: plants application — use cases, фасады api, AccessPolicy, фейки, тесты допущений

**Files:**
- Create: `src/main/java/com/plantarena/plants/application/port/in/SubmitPlantUseCase.java`
- Create: `src/main/java/com/plantarena/plants/application/port/in/ListPlantsUseCase.java`
- Create: `src/main/java/com/plantarena/plants/application/port/in/GetPlantUseCase.java`
- Create: `src/main/java/com/plantarena/plants/application/port/in/RenamePlantUseCase.java`
- Create: `src/main/java/com/plantarena/plants/application/port/in/ArchivePlantUseCase.java`
- Create: `src/main/java/com/plantarena/plants/application/port/in/GetPlantModerationUseCase.java`
- Create: `src/main/java/com/plantarena/plants/application/port/out/MediaAssetsGateway.java`
- Create: `src/main/java/com/plantarena/plants/application/port/out/MediaAssetClaimsGateway.java`
- Create: `src/main/java/com/plantarena/plants/application/AssetNotFoundException.java`
- Create: `src/main/java/com/plantarena/plants/application/AssetAlreadyClaimedException.java`
- Create: `src/main/java/com/plantarena/plants/application/ImageRestrictedException.java`
- Create: `src/main/java/com/plantarena/plants/application/PlantUnderReservationException.java`
- Create: `src/main/java/com/plantarena/plants/application/PlantsAccessPolicy.java`
- Create: `src/main/java/com/plantarena/plants/application/PlantService.java`
- Create: `src/main/java/com/plantarena/plants/application/PlantModerationService.java`
- Create: `src/main/java/com/plantarena/plants/application/PlantEligibilityService.java`
- Create: `src/main/java/com/plantarena/plants/application/PlantLifecycleService.java`
- Create (test): `src/test/java/com/plantarena/plants/application/support/InMemoryPlantRepository.java`
- Create (test): `src/test/java/com/plantarena/plants/application/support/InMemoryImageRestrictionRepository.java`
- Create (test): `src/test/java/com/plantarena/plants/application/support/InMemoryPlantReservationRepository.java`
- Create (test): `src/test/java/com/plantarena/plants/application/support/FakeMediaAssets.java`
- Create (test): `src/test/java/com/plantarena/plants/application/support/FakeMediaAssetClaims.java`
- Create (test): `src/test/java/com/plantarena/plants/application/support/FakeIntegrationEventPublisher.java`
- Test: `src/test/java/com/plantarena/plants/application/PlantServiceTest.java`
- Test: `src/test/java/com/plantarena/plants/application/PlantModerationServiceTest.java`
- Test: `src/test/java/com/plantarena/plants/application/PlantEligibilityServiceTest.java`
- Test: `src/test/java/com/plantarena/plants/application/PlantLifecycleServiceTest.java`

**Interfaces:**
- Consumes: домен Task 2; `plants.api` Task 1 (сервисы реализуют фасады); `shared.security.CurrentActor`; `shared.event.IntegrationEventPublisher`; `java.time.Clock` (бин из `ClockConfig`).
- Produces: бины `PlantService` (6 use cases), `PlantModerationService` (`PlantModeration`), `PlantEligibilityService` (`PlantEligibility`), `PlantLifecycleService` (`PlantLifecycle`), `PlantsAccessPolicy`; порты `MediaAssetsGateway`/`MediaAssetClaimsGateway` (реализует ACL в Task 6, фейки — в тестах); исключения с кодами для web (Task 6): `ASSET_NOT_FOUND`, `ASSET_ALREADY_CLAIMED`, `IMAGE_RESTRICTED` (+`retryAt`), `PLANT_UNDER_RESERVATION`.

- [ ] **Step 1: Входные и выходные порты**

`src/main/java/com/plantarena/plants/application/port/in/SubmitPlantUseCase.java`:

```java
package com.plantarena.plants.application.port.in;

import com.plantarena.plants.api.PlantData;
import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/** Подача заявки «это моё растение» (раздел 13: POST /plants). */
public interface SubmitPlantUseCase {

    PlantData submit(CurrentActor actor, SubmitPlantCommand command);

    record SubmitPlantCommand(UUID assetId, String title) {
    }
}
```

`src/main/java/com/plantarena/plants/application/port/in/ListPlantsUseCase.java`:

```java
package com.plantarena.plants.application.port.in;

import com.plantarena.plants.api.PlantData;
import com.plantarena.shared.security.CurrentActor;
import java.util.List;
import java.util.UUID;

/** Список растений с учётом видимости (раздел 13: GET /plants, X-Total-Count). */
public interface ListPlantsUseCase {

    /**
     * @param ownerId владелец фильтра; null — свои растения
     */
    PlantListResult list(CurrentActor actor, UUID ownerId, int page, int size);

    record PlantListResult(List<PlantData> items, long total) {
    }
}
```

`src/main/java/com/plantarena/plants/application/port/in/GetPlantUseCase.java`:

```java
package com.plantarena.plants.application.port.in;

import com.plantarena.plants.api.PlantData;
import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/** Просмотр растения с учётом видимости (раздел 13: GET /plants/{id}). */
public interface GetPlantUseCase {

    PlantData get(CurrentActor actor, UUID plantId);
}
```

`src/main/java/com/plantarena/plants/application/port/in/RenamePlantUseCase.java`:

```java
package com.plantarena.plants.application.port.in;

import com.plantarena.plants.api.PlantData;
import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/** Переименование владельцем (раздел 13: PATCH /plants/{id}, только title). */
public interface RenamePlantUseCase {

    PlantData rename(CurrentActor actor, UUID plantId, String title);
}
```

`src/main/java/com/plantarena/plants/application/port/in/ArchivePlantUseCase.java`:

```java
package com.plantarena.plants.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/** Архивация владельцем вне активного резерва (раздел 13: DELETE /plants/{id}). */
public interface ArchivePlantUseCase {

    void archive(CurrentActor actor, UUID plantId);
}
```

`src/main/java/com/plantarena/plants/application/port/in/GetPlantModerationUseCase.java`:

```java
package com.plantarena.plants.application.port.in;

import com.plantarena.plants.api.PlantModerationStatus;
import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/** Статус модерации для владельца (раздел 13: GET /plants/{id}/moderation). */
public interface GetPlantModerationUseCase {

    ModerationStatusResult moderation(CurrentActor actor, UUID plantId);

    record ModerationStatusResult(PlantModerationStatus moderationStatus, String reason,
                                  boolean retryUploadAllowed) {
    }
}
```

`src/main/java/com/plantarena/plants/application/port/out/MediaAssetsGateway.java`:

```java
package com.plantarena.plants.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Выходной порт plants: метаданные asset из media (Customer–Supplier,
 * раздел 4.3). Потребитель владеет портом со своими типами (ACL);
 * реализация — plants.adapter.out.media (in-process, лаба №2 — Feign).
 */
public interface MediaAssetsGateway {

    Optional<AssetMetadata> findById(UUID assetId);

    /** ACL-DTO: только нужное plants; storageKey не пересекает границу. */
    record AssetMetadata(UUID id, UUID ownerId, String fingerprint, int fingerprintVersion) {
    }
}
```

`src/main/java/com/plantarena/plants/application/port/out/MediaAssetClaimsGateway.java`:

```java
package com.plantarena.plants.application.port.out;

import java.util.UUID;

/**
 * Выходной порт plants: команды задействованности файла в media (ADR-008).
 * media — upstream и не может зависеть от plants, поэтому plants сообщает
 * media о занятости и публичности asset командами через media.api.
 */
public interface MediaAssetClaimsGateway {

    /** Задействовать файл за растением; publiclyVisible — после одобрения. Идемпотентно (upsert). */
    void claim(UUID assetId, UUID plantId, boolean publiclyVisible);

    /** Освободить файл растения (архивация). Идемпотентно. */
    void release(UUID assetId, UUID plantId);
}
```

- [ ] **Step 2: Исключения application + PlantsAccessPolicy**

`src/main/java/com/plantarena/plants/application/AssetNotFoundException.java`:

```java
package com.plantarena.plants.application;

/** Файл не найден или чужой (скрыт приватностью, 404 — как в media). */
public final class AssetNotFoundException extends RuntimeException {

    public AssetNotFoundException(String message) {
        super(message);
    }
}
```

`src/main/java/com/plantarena/plants/application/AssetAlreadyClaimedException.java`:

```java
package com.plantarena.plants.application;

/** Файл уже задействован другим (неархивированным) растением (ADR-008, 409). */
public final class AssetAlreadyClaimedException extends RuntimeException {

    public AssetAlreadyClaimedException(String message) {
        super(message);
    }
}
```

`src/main/java/com/plantarena/plants/application/ImageRestrictedException.java`:

```java
package com.plantarena.plants.application;

import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.RestrictionKind;
import java.time.Instant;

/** Изображение запрещено для повторного использования (раздел 13: 409 + retryAt). */
public final class ImageRestrictedException extends RuntimeException {

    private final RestrictionKind kind;
    private final Instant retryAt;

    public ImageRestrictedException(ImageRestriction restriction) {
        super("Изображение запрещено: " + restriction.kind() + " (" + restriction.reason() + ")");
        this.kind = restriction.kind();
        this.retryAt = restriction.expiresAt(); // null для PERMANENT
    }

    public RestrictionKind kind() {
        return kind;
    }

    /** Момент, когда COOLDOWN истечёт; null для PERMANENT (раздел 13). */
    public Instant retryAt() {
        return retryAt;
    }
}
```

`src/main/java/com/plantarena/plants/application/PlantUnderReservationException.java`:

```java
package com.plantarena.plants.application;

/** Растение под активным резервом — архивация запрещена (раздел 13: 409). */
public final class PlantUnderReservationException extends RuntimeException {

    public PlantUnderReservationException(String message) {
        super(message);
    }
}
```

`src/main/java/com/plantarena/plants/application/PlantsAccessPolicy.java`:

```java
package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import org.springframework.stereotype.Component;

/**
 * AccessPolicy контекста plants (раздел 2): правила единого языка контекста.
 * Видимость: своё растение — всегда; чужое — только APPROVED (черновики и
 * отклонённые не раскрываются, раздел 13); админ — служебный доступ ко всем
 * неархивированным. Изменения (rename/archive) — только владелец (раздел 13
 * не даёт админу прав на чужие растения). Скрытое — 404, не 403.
 */
@Component
public class PlantsAccessPolicy {

    public void requireIdentified(CurrentActor actor) {
        if (actor == null || actor.isGuest()) {
            throw new NotIdentifiedException(
                "Требуется идентифицированный пользователь (X-Demo-User-Id в dev/test, ADR-005)");
        }
    }

    /** Просмотр: владелец, админ или APPROVED-растение; иначе скрыто (404). */
    public void requirePlantViewer(CurrentActor actor, Plant plant) {
        requireIdentified(actor);
        if (isOwner(actor, plant) || actor.hasRole(AppRole.ADMIN)
                || plant.moderationStatus() == ModerationStatus.APPROVED) {
            return;
        }
        throw new PlantNotFoundException("Растение не найдено: " + plant.id());
    }

    /** Изменение: только владелец (вызывается после requirePlantViewer). */
    public void requirePlantOwner(CurrentActor actor, Plant plant) {
        if (isOwner(actor, plant)) {
            return;
        }
        throw new AccessDeniedException("Действие с растением доступно только владельцу");
    }

    /** Статус модерации — приватная информация владельца (раздел 13). */
    public void requireModerationViewer(CurrentActor actor, Plant plant) {
        requireIdentified(actor);
        if (isOwner(actor, plant)) {
            return;
        }
        throw new PlantNotFoundException("Растение не найдено: " + plant.id());
    }

    private boolean isOwner(CurrentActor actor, Plant plant) {
        return actor.userId() != null && actor.userId().equals(plant.ownerId());
    }
}
```

- [ ] **Step 3: PlantService (6 use cases)**

`src/main/java/com/plantarena/plants/application/PlantService.java`:

```java
package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantData;
import com.plantarena.plants.api.PlantLifeStatus;
import com.plantarena.plants.api.PlantModerationStatus;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.event.PlantSubmittedEvent;
import com.plantarena.plants.application.port.in.ArchivePlantUseCase;
import com.plantarena.plants.application.port.in.GetPlantModerationUseCase;
import com.plantarena.plants.application.port.in.GetPlantUseCase;
import com.plantarena.plants.application.port.in.ListPlantsUseCase;
import com.plantarena.plants.application.port.in.RenamePlantUseCase;
import com.plantarena.plants.application.port.in.SubmitPlantUseCase;
import com.plantarena.plants.application.port.out.MediaAssetClaimsGateway;
import com.plantarena.plants.application.port.out.MediaAssetsGateway;
import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.ImageReusePolicy;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import com.plantarena.plants.domain.PlantReservationRepository;
import com.plantarena.shared.event.IntegrationEventPublisher;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases растений (разделы 6 и 13). Подача — межконтекстный процесс
 * «Plant (plants) + claim (media)» в одной транзакции монолита (раздел 12,
 * ADR-008); в лабе №2 — команда в file-service с retry/компенсацией.
 * Публикует PlantSubmitted (moderation подписан, итерация 4).
 */
@Service
public class PlantService implements SubmitPlantUseCase, ListPlantsUseCase, GetPlantUseCase,
        RenamePlantUseCase, ArchivePlantUseCase, GetPlantModerationUseCase {

    private final PlantRepository plants;
    private final ImageRestrictionRepository restrictions;
    private final PlantReservationRepository reservations;
    private final MediaAssetsGateway mediaAssets;
    private final MediaAssetClaimsGateway mediaClaims;
    private final PlantsAccessPolicy accessPolicy;
    private final IntegrationEventPublisher eventPublisher;
    private final ImageReusePolicy reusePolicy = new ImageReusePolicy();
    private final Clock clock;

    public PlantService(PlantRepository plants, ImageRestrictionRepository restrictions,
                        PlantReservationRepository reservations, MediaAssetsGateway mediaAssets,
                        MediaAssetClaimsGateway mediaClaims, PlantsAccessPolicy accessPolicy,
                        IntegrationEventPublisher eventPublisher, Clock clock) {
        this.plants = plants;
        this.restrictions = restrictions;
        this.reservations = reservations;
        this.mediaAssets = mediaAssets;
        this.mediaClaims = mediaClaims;
        this.accessPolicy = accessPolicy;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PlantData submit(CurrentActor actor, SubmitPlantCommand command) {
        accessPolicy.requireIdentified(actor);
        MediaAssetsGateway.AssetMetadata asset = mediaAssets.findById(command.assetId())
            .orElseThrow(() -> new AssetNotFoundException("Файл не найден: " + command.assetId()));
        if (!asset.ownerId().equals(actor.userId())) {
            throw new AssetNotFoundException("Файл не найден"); // чужой скрыт (раздел 13)
        }
        plants.findActiveByAssetId(asset.id()).ifPresent(existing -> {
            throw new AssetAlreadyClaimedException(
                "Файл уже задействован растением: " + existing.id());
        });
        ImageFingerprint fingerprint =
            new ImageFingerprint(asset.fingerprint(), asset.fingerprintVersion());
        requireNotRestricted(actor.userId(), fingerprint);

        Plant plant = Plant.submit(actor.userId(), asset.id(), fingerprint,
            command.title(), clock.instant());
        plants.save(plant);
        mediaClaims.claim(asset.id(), plant.id(), false); // занят, но не публичен (ADR-008)

        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new PlantSubmittedEvent(eventId, PlantSubmittedEvent.TYPE,
            PlantSubmittedEvent.SCHEMA_VERSION, plant.id(), plant.version(), clock.instant(),
            eventId, // correlationId: сквозная корреляция появится с Kafka (лаба №4)
            new PlantSubmittedEvent.Payload(plant.id(), plant.ownerId(), plant.assetId(),
                plant.fingerprint().value(), plant.fingerprint().algorithmVersion())));
        return toData(plant);
    }

    @Override
    @Transactional(readOnly = true)
    public PlantListResult list(CurrentActor actor, UUID ownerId, int page, int size) {
        accessPolicy.requireIdentified(actor);
        UUID targetOwner = ownerId == null ? actor.userId() : ownerId;
        boolean ownView = targetOwner.equals(actor.userId()) || actor.hasRole(AppRole.ADMIN);
        List<Plant> items = ownView
            ? plants.findByOwner(targetOwner, page * size, size)
            : plants.findApprovedByOwner(targetOwner, page * size, size);
        long total = ownView
            ? plants.countByOwner(targetOwner)
            : plants.countApprovedByOwner(targetOwner);
        return new PlantListResult(items.stream().map(PlantService::toData).toList(), total);
    }

    @Override
    @Transactional(readOnly = true)
    public PlantData get(CurrentActor actor, UUID plantId) {
        Plant plant = loadVisible(plantId);
        accessPolicy.requirePlantViewer(actor, plant);
        return toData(plant);
    }

    @Override
    @Transactional
    public PlantData rename(CurrentActor actor, UUID plantId, String title) {
        Plant plant = loadVisible(plantId);
        accessPolicy.requirePlantViewer(actor, plant);
        accessPolicy.requirePlantOwner(actor, plant);
        plant.rename(title);
        return toData(plants.save(plant));
    }

    @Override
    @Transactional
    public void archive(CurrentActor actor, UUID plantId) {
        Plant plant = loadVisible(plantId);
        accessPolicy.requirePlantViewer(actor, plant);
        accessPolicy.requirePlantOwner(actor, plant);
        if (reservations.findActiveByPlantId(plant.id()).isPresent()) {
            throw new PlantUnderReservationException(
                "Растение под активным резервом изображения: " + plant.id());
        }
        plant.archive(clock.instant());
        plants.save(plant);
        mediaClaims.release(plant.assetId(), plant.id()); // файл освобождён (ADR-008)
    }

    @Override
    @Transactional(readOnly = true)
    public ModerationStatusResult moderation(CurrentActor actor, UUID plantId) {
        Plant plant = loadVisible(plantId);
        accessPolicy.requireModerationViewer(actor, plant);
        return new ModerationStatusResult(
            PlantModerationStatus.valueOf(plant.moderationStatus().name()),
            plant.moderationReason(),
            plant.moderationStatus() == ModerationStatus.REJECTED);
    }

    private void requireNotRestricted(UUID ownerId, ImageFingerprint fingerprint) {
        List<ImageRestriction> history =
            restrictions.findByOwnerAndFingerprint(ownerId, fingerprint.value());
        reusePolicy.activeRestriction(history, clock.instant())
            .ifPresent(restriction -> {
                throw new ImageRestrictedException(restriction);
            });
    }

    /** Архивированное растение скрыто для всех, включая владельца (раздел 13). */
    private Plant loadVisible(UUID plantId) {
        Plant plant = plants.findById(plantId)
            .orElseThrow(() -> new PlantNotFoundException("Растение не найдено: " + plantId));
        if (plant.archivedAt() != null) {
            throw new PlantNotFoundException("Растение не найдено: " + plantId);
        }
        return plant;
    }

    private static PlantData toData(Plant plant) {
        return new PlantData(plant.id(), plant.ownerId(), plant.assetId(), plant.title(),
            PlantModerationStatus.valueOf(plant.moderationStatus().name()),
            PlantLifeStatus.valueOf(plant.lifeStatus().name()),
            plant.createdAt(), plant.diedAt(), plant.archivedAt());
    }
}
```

- [ ] **Step 4: Фасадные сервисы api (модерация, допуск, гибель)**

`src/main/java/com/plantarena/plants/application/PlantModerationService.java`:

```java
package com.plantarena.plants.application;

import com.plantarena.plants.api.ModerationAlreadyDecidedException;
import com.plantarena.plants.api.PlantData;
import com.plantarena.plants.api.PlantLifeStatus;
import com.plantarena.plants.api.PlantModeration;
import com.plantarena.plants.api.PlantModerationStatus;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.event.PlantModerationDecidedEvent;
import com.plantarena.plants.application.port.out.MediaAssetClaimsGateway;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantAlreadyDecidedException;
import com.plantarena.plants.domain.PlantRepository;
import com.plantarena.shared.event.IntegrationEventPublisher;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация опубликованного контракта PlantModeration (раздел 4.3):
 * решение moderation приходит командой. Одобрение делает файл растения
 * публичным в media (ADR-008). Публикует PlantModerationDecided
 * (tournaments подписан, итерация 5).
 */
@Service
public class PlantModerationService implements PlantModeration {

    private final PlantRepository plants;
    private final MediaAssetClaimsGateway mediaClaims;
    private final IntegrationEventPublisher eventPublisher;
    private final Clock clock;

    public PlantModerationService(PlantRepository plants, MediaAssetClaimsGateway mediaClaims,
                                  IntegrationEventPublisher eventPublisher, Clock clock) {
        this.plants = plants;
        this.mediaClaims = mediaClaims;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PlantData recordDecision(UUID plantId, Decision decision, String reason) {
        Plant plant = plants.findById(plantId)
            .orElseThrow(() -> new PlantNotFoundException("Растение не найдено: " + plantId));
        ModerationStatus target = ModerationStatus.valueOf(decision.name());
        boolean changed;
        try {
            changed = plant.applyDecision(target, reason);
        } catch (PlantAlreadyDecidedException e) {
            throw new ModerationAlreadyDecidedException(e.getMessage());
        }
        if (!changed) {
            return toData(plant); // идемпотентный повтор доставки того же решения
        }
        plants.save(plant);
        if (decision == Decision.APPROVED) {
            mediaClaims.claim(plant.assetId(), plant.id(), true); // публично (ADR-008)
        }
        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new PlantModerationDecidedEvent(eventId,
            PlantModerationDecidedEvent.TYPE, PlantModerationDecidedEvent.SCHEMA_VERSION,
            plant.id(), plant.version(), clock.instant(), eventId,
            new PlantModerationDecidedEvent.Payload(plant.id(), plant.ownerId(),
                decision.name(), reason)));
        return toData(plant);
    }

    private static PlantData toData(Plant plant) {
        return new PlantData(plant.id(), plant.ownerId(), plant.assetId(), plant.title(),
            PlantModerationStatus.valueOf(plant.moderationStatus().name()),
            PlantLifeStatus.valueOf(plant.lifeStatus().name()),
            plant.createdAt(), plant.diedAt(), plant.archivedAt());
    }
}
```

`src/main/java/com/plantarena/plants/application/PlantEligibilityService.java`:

```java
package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantEligibility;
import com.plantarena.plants.api.PlantNotEligibleException;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.ReservationConflictException;
import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.ImageReusePolicy;
import com.plantarena.plants.domain.LifeStatus;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import com.plantarena.plants.domain.PlantReservation;
import com.plantarena.plants.domain.PlantReservationRepository;
import com.plantarena.plants.domain.ImageRestrictionRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация опубликованного контракта PlantEligibility (раздел 6): проверки
 * и изменение резерва атомарны. Гонку двух одновременных резервов одной пары
 * (ownerId, fingerprint) закрывает частичный уникальный индекс PostgreSQL —
 * нарушение переводится в ReservationConflictException (допущение 4).
 */
@Service
public class PlantEligibilityService implements PlantEligibility {

    private final PlantRepository plants;
    private final ImageRestrictionRepository restrictions;
    private final PlantReservationRepository reservations;
    private final ImageReusePolicy reusePolicy = new ImageReusePolicy();
    private final Clock clock;

    public PlantEligibilityService(PlantRepository plants,
                                   ImageRestrictionRepository restrictions,
                                   PlantReservationRepository reservations, Clock clock) {
        this.plants = plants;
        this.restrictions = restrictions;
        this.reservations = reservations;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID reserveSubmission(UUID ownerId, UUID plantId, UUID idempotencyKey) {
        PlantReservation existing = reservations.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return existing.id(); // идемпотентный повтор команды (раздел 6)
        }
        Plant plant = plants.findById(plantId)
            .orElseThrow(() -> new PlantNotFoundException("Растение не найдено: " + plantId));
        if (!plant.ownerId().equals(ownerId)) {
            throw new PlantNotEligibleException(PlantNotEligibleException.Reason.NOT_OWNER,
                "Растение принадлежит другому владельцу");
        }
        if (plant.lifeStatus() != LifeStatus.ALIVE) {
            throw new PlantNotEligibleException(PlantNotEligibleException.Reason.DEAD,
                "Растение погибло и не может участвовать");
        }
        if (plant.moderationStatus() == ModerationStatus.REJECTED) {
            throw new PlantNotEligibleException(PlantNotEligibleException.Reason.NOT_RESERVABLE,
                "Отклонённая заявка не резервируется");
        }
        requireNotRestricted(ownerId, plant);

        reservations.findActiveByOwnerAndFingerprint(ownerId, plant.fingerprint().value())
            .ifPresent(active -> {
                throw new ReservationConflictException(
                    "Изображение уже зарезервировано заявкой: " + active.plantId());
            });
        try {
            PlantReservation saved = reservations.save(PlantReservation.reserve(
                ownerId, plant.id(), plant.fingerprint(), idempotencyKey, clock.instant()));
            return saved.id();
        } catch (DataIntegrityViolationException e) {
            // гонка: частичный уникальный индекс (owner_id, fingerprint) WHERE ACTIVE
            throw new ReservationConflictException(
                "Изображение уже зарезервировано другой заявкой (гонка)");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void confirmEligibility(UUID ownerId, UUID plantId, UUID reservationId) {
        Plant plant = plants.findById(plantId)
            .orElseThrow(() -> new PlantNotFoundException("Растение не найдено: " + plantId));
        if (!plant.ownerId().equals(ownerId)) {
            throw new PlantNotEligibleException(PlantNotEligibleException.Reason.NOT_OWNER,
                "Растение принадлежит другому владельцу");
        }
        if (plant.lifeStatus() != LifeStatus.ALIVE) {
            throw new PlantNotEligibleException(PlantNotEligibleException.Reason.DEAD,
                "Растение погибло и не может участвовать");
        }
        if (plant.moderationStatus() != ModerationStatus.APPROVED) {
            throw new PlantNotEligibleException(PlantNotEligibleException.Reason.NOT_APPROVED,
                "К старту допускается только одобренное растение (раздел 6)");
        }
        PlantReservation reservation = reservations.findById(reservationId).orElse(null);
        if (reservation == null
                || reservation.status() != com.plantarena.plants.domain.ReservationStatus.ACTIVE
                || !reservation.plantId().equals(plantId)
                || !reservation.ownerId().equals(ownerId)
                || !reservation.fingerprint().equals(plant.fingerprint())) {
            throw new PlantNotEligibleException(
                PlantNotEligibleException.Reason.NO_ACTIVE_RESERVATION,
                "Активный резерв этой заявки не найден");
        }
    }

    @Override
    @Transactional
    public void releaseReservation(UUID reservationId) {
        reservations.findById(reservationId).ifPresent(reservation -> {
            if (reservation.release(clock.instant())) {
                reservations.save(reservation);
            }
        });
    }

    private void requireNotRestricted(UUID ownerId, Plant plant) {
        List<ImageRestriction> history = restrictions.findByOwnerAndFingerprint(
            ownerId, plant.fingerprint().value());
        reusePolicy.activeRestriction(history, clock.instant()).ifPresent(restriction -> {
            throw new PlantNotEligibleException(PlantNotEligibleException.Reason.RESTRICTED,
                "Изображение запрещено: " + restriction.kind(), restriction.expiresAt());
        });
    }
}
```

`src/main/java/com/plantarena/plants/application/PlantLifecycleService.java`:

```java
package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantLifecycle;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.event.PlantDiedEvent;
import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.LifeStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import com.plantarena.plants.domain.ImageRestrictionRepository;
import com.plantarena.shared.event.IntegrationEventPublisher;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация опубликованного контракта PlantLifecycle (раздел 4.3): гибель
 * приходит командой от tournaments. Межагрегатная транзакция «Plant +
 * ImageRestriction» — отступление от «одна транзакция — один агрегат»,
 * описанное в ADR-008 (процесс закрытия окна, раздел 12.3); в лабе №2/4 —
 * надёжная команда/событие с повтором. Идемпотентна: DEAD-растение — no-op
 * (рестарт без повторной гибели и без дубля запрета).
 */
@Service
public class PlantLifecycleService implements PlantLifecycle {

    private final PlantRepository plants;
    private final ImageRestrictionRepository restrictions;
    private final IntegrationEventPublisher eventPublisher;
    private final Clock clock;

    public PlantLifecycleService(PlantRepository plants, ImageRestrictionRepository restrictions,
                                 IntegrationEventPublisher eventPublisher, Clock clock) {
        this.plants = plants;
        this.restrictions = restrictions;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void registerDeath(UUID plantId, RestrictionKind kind, Instant cooldownExpiresAt,
                              String reason, UUID sourceEntryId) {
        Objects.requireNonNull(kind, "kind");
        if (kind == RestrictionKind.COOLDOWN && cooldownExpiresAt == null) {
            throw new IllegalArgumentException("COOLDOWN требует cooldownExpiresAt");
        }
        if (kind == RestrictionKind.PERMANENT && cooldownExpiresAt != null) {
            throw new IllegalArgumentException("PERMANENT не принимает cooldownExpiresAt");
        }
        Plant plant = plants.findById(plantId)
            .orElseThrow(() -> new PlantNotFoundException("Растение не найдено: " + plantId));
        if (plant.lifeStatus() == LifeStatus.DEAD) {
            return; // идемпотентность повтора доставки
        }
        plant.die(clock.instant());
        plants.save(plant);

        ImageRestriction restriction = switch (kind) {
            case PERMANENT -> ImageRestriction.permanent(plant.ownerId(), plant.fingerprint(),
                reason, sourceEntryId, clock.instant());
            case COOLDOWN -> ImageRestriction.cooldown(plant.ownerId(), plant.fingerprint(),
                reason, sourceEntryId, cooldownExpiresAt, clock.instant());
        };
        restrictions.save(restriction); // append-only история (раздел 6)

        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new PlantDiedEvent(eventId, PlantDiedEvent.TYPE,
            PlantDiedEvent.SCHEMA_VERSION, plant.id(), plant.version(), clock.instant(), eventId,
            new PlantDiedEvent.Payload(plant.id(), plant.ownerId(),
                plant.fingerprint().value(), kind.name(), sourceEntryId)));
    }
}
```

(В `PlantLifecycleService` добавить `import java.time.Instant;` к остальным импортам.)

- [ ] **Step 5: Фейки (test-дерево)**

`src/test/java/com/plantarena/plants/application/support/InMemoryPlantRepository.java`:

```java
package com.plantarena.plants.application.support;

import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory фейк для application-тестов и контрактных тестов репозитория. */
public class InMemoryPlantRepository implements PlantRepository {

    public final Map<UUID, Plant> plants = new ConcurrentHashMap<>();

    @Override
    public Plant save(Plant plant) {
        plants.put(plant.id(), plant);
        return plant;
    }

    @Override
    public Optional<Plant> findById(UUID id) {
        return Optional.ofNullable(plants.get(id));
    }

    @Override
    public Optional<Plant> findActiveByAssetId(UUID assetId) {
        return plants.values().stream()
            .filter(plant -> plant.assetId().equals(assetId) && plant.archivedAt() == null)
            .findFirst();
    }

    @Override
    public List<Plant> findByOwner(UUID ownerId, int offset, int limit) {
        return plants.values().stream()
            .filter(plant -> plant.ownerId().equals(ownerId) && plant.archivedAt() == null)
            .sorted(Comparator.comparing(Plant::id))
            .skip(offset)
            .limit(limit)
            .toList();
    }

    @Override
    public long countByOwner(UUID ownerId) {
        return plants.values().stream()
            .filter(plant -> plant.ownerId().equals(ownerId) && plant.archivedAt() == null)
            .count();
    }

    @Override
    public List<Plant> findApprovedByOwner(UUID ownerId, int offset, int limit) {
        return plants.values().stream()
            .filter(plant -> plant.ownerId().equals(ownerId) && plant.archivedAt() == null
                && plant.moderationStatus() == com.plantarena.plants.domain.ModerationStatus.APPROVED)
            .sorted(Comparator.comparing(Plant::id))
            .skip(offset)
            .limit(limit)
            .toList();
    }

    @Override
    public long countApprovedByOwner(UUID ownerId) {
        return plants.values().stream()
            .filter(plant -> plant.ownerId().equals(ownerId) && plant.archivedAt() == null
                && plant.moderationStatus() == com.plantarena.plants.domain.ModerationStatus.APPROVED)
            .count();
    }
}
```

`src/test/java/com/plantarena/plants/application/support/InMemoryImageRestrictionRepository.java`:

```java
package com.plantarena.plants.application.support;

import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.ImageRestrictionRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory фейк: append-only список запретов. */
public class InMemoryImageRestrictionRepository implements ImageRestrictionRepository {

    public final List<ImageRestriction> restrictions = new CopyOnWriteArrayList<>();

    @Override
    public ImageRestriction save(ImageRestriction restriction) {
        restrictions.add(restriction);
        return restriction;
    }

    @Override
    public List<ImageRestriction> findByOwnerAndFingerprint(UUID ownerId, String fingerprintValue) {
        return restrictions.stream()
            .filter(restriction -> restriction.ownerId().equals(ownerId)
                && restriction.fingerprint().value().equals(fingerprintValue))
            .toList();
    }
}
```

`src/test/java/com/plantarena/plants/application/support/InMemoryPlantReservationRepository.java`:

```java
package com.plantarena.plants.application.support;

import com.plantarena.plants.domain.PlantReservation;
import com.plantarena.plants.domain.PlantReservationRepository;
import com.plantarena.plants.domain.ReservationStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * In-memory фейк, честный к set-инварианту (контрактные тесты): активный резерв
 * один на пару (ownerId, fingerprint) — как частичный уникальный индекс БД.
 */
public class InMemoryPlantReservationRepository implements PlantReservationRepository {

    public final Map<UUID, PlantReservation> reservations = new ConcurrentHashMap<>();

    @Override
    public PlantReservation save(PlantReservation reservation) {
        reservations.values().stream()
            .filter(existing -> existing.status() == ReservationStatus.ACTIVE
                && existing.ownerId().equals(reservation.ownerId())
                && existing.fingerprint().value().equals(reservation.fingerprint().value())
                && !existing.id().equals(reservation.id()))
            .findAny()
            .ifPresent(existing -> {
                throw new DataIntegrityViolationException(
                    "Активный резерв пары уже существует: " + existing.id());
            });
        reservations.put(reservation.id(), reservation);
        return reservation;
    }

    @Override
    public Optional<PlantReservation> findById(UUID id) {
        return Optional.ofNullable(reservations.get(id));
    }

    @Override
    public Optional<PlantReservation> findByIdempotencyKey(UUID idempotencyKey) {
        return reservations.values().stream()
            .filter(reservation -> reservation.idempotencyKey().equals(idempotencyKey))
            .findFirst();
    }

    @Override
    public Optional<PlantReservation> findActiveByOwnerAndFingerprint(UUID ownerId,
                                                                      String fingerprintValue) {
        return reservations.values().stream()
            .filter(reservation -> reservation.status() == ReservationStatus.ACTIVE
                && reservation.ownerId().equals(ownerId)
                && reservation.fingerprint().value().equals(fingerprintValue))
            .findFirst();
    }

    @Override
    public Optional<PlantReservation> findActiveByPlantId(UUID plantId) {
        return reservations.values().stream()
            .filter(reservation -> reservation.status() == ReservationStatus.ACTIVE
                && reservation.plantId().equals(plantId))
            .findFirst();
    }

    public List<PlantReservation> sorted() {
        return reservations.values().stream()
            .sorted(Comparator.comparing(PlantReservation::id))
            .toList();
    }
}
```

`src/test/java/com/plantarena/plants/application/support/FakeMediaAssets.java`:

```java
package com.plantarena.plants.application.support;

import com.plantarena.plants.application.port.out.MediaAssetsGateway;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Фейк порта MediaAssetsGateway: метаданные задаёт тест. */
public class FakeMediaAssets implements MediaAssetsGateway {

    public final Map<UUID, AssetMetadata> assets = new HashMap<>();

    public FakeMediaAssets addAsset(UUID assetId, UUID ownerId, String fingerprint) {
        assets.put(assetId, new AssetMetadata(assetId, ownerId, fingerprint, 1));
        return this;
    }

    @Override
    public Optional<AssetMetadata> findById(UUID assetId) {
        return Optional.ofNullable(assets.get(assetId));
    }
}
```

`src/test/java/com/plantarena/plants/application/support/FakeMediaAssetClaims.java`:

```java
package com.plantarena.plants.application.support;

import com.plantarena.plants.application.port.out.MediaAssetClaimsGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Фейк порта MediaAssetClaimsGateway: журнал команд + состояние занятости. */
public class FakeMediaAssetClaims implements MediaAssetClaimsGateway {

    public final List<String> calls = new ArrayList<>();
    public final List<UUID> claimedAssets = new ArrayList<>();
    public final List<UUID> publicAssets = new ArrayList<>();

    @Override
    public void claim(UUID assetId, UUID plantId, boolean publiclyVisible) {
        calls.add("claim " + assetId + " " + plantId + " public=" + publiclyVisible);
        if (!claimedAssets.contains(assetId)) {
            claimedAssets.add(assetId);
        }
        if (publiclyVisible && !publicAssets.contains(assetId)) {
            publicAssets.add(assetId);
        }
        if (!publiclyVisible) {
            publicAssets.remove(assetId);
        }
    }

    @Override
    public void release(UUID assetId, UUID plantId) {
        calls.add("release " + assetId + " " + plantId);
        claimedAssets.remove(assetId);
        publicAssets.remove(assetId);
    }
}
```

`src/test/java/com/plantarena/plants/application/support/FakeIntegrationEventPublisher.java`:

```java
package com.plantarena.plants.application.support;

import com.plantarena.shared.event.IntegrationEvent;
import com.plantarena.shared.event.IntegrationEventPublisher;
import java.util.ArrayList;
import java.util.List;

/** Фейк публикации событий: собирает опубликованные события для проверок. */
public class FakeIntegrationEventPublisher implements IntegrationEventPublisher {

    public final List<IntegrationEvent> published = new ArrayList<>();

    @Override
    public void publish(IntegrationEvent event) {
        published.add(event);
    }
}
```

- [ ] **Step 6: Application-тесты (включая именованные тесты допущений 1–5)**

`src/test/java/com/plantarena/plants/application/PlantServiceTest.java`:

```java
package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.event.PlantSubmittedEvent;
import com.plantarena.plants.application.port.in.SubmitPlantUseCase;
import com.plantarena.plants.application.support.FakeMediaAssetClaims;
import com.plantarena.plants.application.support.FakeMediaAssets;
import com.plantarena.plants.application.support.FakeIntegrationEventPublisher;
import com.plantarena.plants.application.support.InMemoryImageRestrictionRepository;
import com.plantarena.plants.application.support.InMemoryPlantRepository;
import com.plantarena.plants.application.support.InMemoryPlantReservationRepository;
import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Use cases растений: подача, видимость, архивация, запреты")
class PlantServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final String FINGERPRINT = "1".repeat(64);
    private static final String OTHER_FINGERPRINT = "2".repeat(64);

    private final PlantRepository plants = new InMemoryPlantRepository();
    private final InMemoryImageRestrictionRepository restrictions =
        new InMemoryImageRestrictionRepository();
    private final InMemoryPlantReservationRepository reservations =
        new InMemoryPlantReservationRepository();
    private final FakeMediaAssets mediaAssets = new FakeMediaAssets();
    private final FakeMediaAssetClaims mediaClaims = new FakeMediaAssetClaims();
    private final FakeIntegrationEventPublisher events = new FakeIntegrationEventPublisher();
    private final PlantService service = new PlantService(plants, restrictions, reservations,
        mediaAssets, mediaClaims, new PlantsAccessPolicy(), events,
        Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID ownerId = UUID.randomUUID();
    private final CurrentActor owner =
        CurrentActor.identified(ownerId, Set.of(AppRole.USER));
    private final CurrentActor stranger =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER));
    private final CurrentActor admin =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER, AppRole.ADMIN));

    private UUID assetOf(UUID ownerId, String fingerprint) {
        UUID assetId = UUID.randomUUID();
        mediaAssets.addAsset(assetId, ownerId, fingerprint);
        return assetId;
    }

    private UUID submittedPlant() {
        return service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetOf(ownerId, FINGERPRINT), "Фикус")).id();
    }

    @Test
    void подача_создаёт_PENDING_растение_занимает_файл_и_публикует_событие() {
        UUID assetId = assetOf(ownerId, FINGERPRINT);

        var result = service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetId, "Мой фикус"));

        assertThat(result.moderationStatus().name()).isEqualTo("PENDING");
        assertThat(mediaClaims.claimedAssets).containsExactly(assetId);
        assertThat(mediaClaims.publicAssets).isEmpty(); // занят, но не публичен
        assertThat(events.published).hasSize(1);
        PlantSubmittedEvent event = (PlantSubmittedEvent) events.published.get(0);
        assertThat(event.eventType()).isEqualTo("PlantSubmitted");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.aggregateId()).isEqualTo(result.id());
        assertThat(event.payload().fingerprint()).isEqualTo(FINGERPRINT);
        assertThat(event.payload().ownerId()).isEqualTo(ownerId);
    }

    @Test
    void гость_не_подает_растение() {
        assertThatThrownBy(() -> service.submit(CurrentActor.guest(),
            new SubmitPlantUseCase.SubmitPlantCommand(UUID.randomUUID(), "Фикус")))
            .isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    void чужой_и_несуществующий_файл_скрыты() {
        assertThatThrownBy(() -> service.submit(stranger,
            new SubmitPlantUseCase.SubmitPlantCommand(assetOf(ownerId, FINGERPRINT), "Чужое")))
            .isInstanceOf(AssetNotFoundException.class);
        assertThatThrownBy(() -> service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(UUID.randomUUID(), "Ничьё")))
            .isInstanceOf(AssetNotFoundException.class);
    }

    @Test
    void файл_нельзя_задействовать_вторым_растением() {
        UUID assetId = assetOf(ownerId, FINGERPRINT);
        service.submit(owner, new SubmitPlantUseCase.SubmitPlantCommand(assetId, "Первое"));

        assertThatThrownBy(() -> service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetId, "Второе")))
            .isInstanceOf(AssetAlreadyClaimedException.class);
    }

    @Test
    void суточный_запрет_блокирует_совпавшую_картинку_до_истечения() {
        restrictions.save(ImageRestriction.cooldown(ownerId,
            new com.plantarena.plants.domain.ImageFingerprint(FINGERPRINT, 1),
            "поражение в глобальном турнире", null, NOW.plus(Duration.ofHours(24)),
            NOW.minusSeconds(1)));

        assertThatThrownBy(() -> service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetOf(ownerId, FINGERPRINT), "Рано")))
            .isInstanceOf(ImageRestrictedException.class)
            .extracting("retryAt")
            .isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void истёкший_суточный_запрет_не_блокирует() {
        restrictions.save(ImageRestriction.cooldown(ownerId,
            new com.plantarena.plants.domain.ImageFingerprint(FINGERPRINT, 1),
            "поражение в глобальном турнире", null, NOW.minusSeconds(1), NOW.minusSeconds(2)));

        var result = service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetOf(ownerId, FINGERPRINT), "Можно"));

        assertThat(result.id()).isNotNull();
    }

    @Test
    void суточный_запрет_касается_только_совпавшей_картинки() {
        restrictions.save(ImageRestriction.cooldown(ownerId,
            new com.plantarena.plants.domain.ImageFingerprint(FINGERPRINT, 1),
            "поражение в глобальном турнире", null, NOW.plus(Duration.ofHours(24)),
            NOW.minusSeconds(1)));

        var result = service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetOf(ownerId, OTHER_FINGERPRINT),
                "Другая картинка сразу после гибели"));

        assertThat(result.id()).isNotNull(); // допущение 5: не вся учётная запись
    }

    @Test
    void чужое_поражение_не_блокирует_фотографию_у_всех() {
        UUID otherOwner = UUID.randomUUID();
        restrictions.save(ImageRestriction.permanent(otherOwner,
            new com.plantarena.plants.domain.ImageFingerprint(FINGERPRINT, 1),
            "поражение в закрытом турнире", null, NOW.minusSeconds(1)));

        var result = service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetOf(ownerId, FINGERPRINT), "Моё такое же"));

        assertThat(result.id()).isNotNull(); // допущение 3: область запрета — пара (ownerId, fingerprint)
    }

    @Test
    void список_чужих_раскрывает_только_одобренные() {
        UUID pending = submittedPlant();
        UUID approved = service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetOf(ownerId, OTHER_FINGERPRINT),
                "Одобренное")).id();
        plants.findById(approved).ifPresent(plant -> {
            plant.applyDecision(com.plantarena.plants.domain.ModerationStatus.APPROVED, null);
            plants.save(plant);
        });

        var strangerView = service.list(stranger, ownerId, 0, 20);
        var ownView = service.list(owner, ownerId, 0, 20);
        var adminView = service.list(admin, ownerId, 0, 20);

        assertThat(strangerView.total()).isEqualTo(1);
        assertThat(strangerView.items().get(0).id()).isEqualTo(approved);
        assertThat(ownView.total()).isEqualTo(2);
        assertThat(adminView.total()).isEqualTo(2);
        assertThat(pending).isNotNull();
    }

    @Test
    void просмотр_скрытых_и_архивированных_растений_404() {
        UUID plantId = submittedPlant();
        Plant plant = plants.findById(plantId).orElseThrow();
        plant.archive(NOW);
        plants.save(plant);

        assertThatThrownBy(() -> service.get(owner, plantId))
            .isInstanceOf(PlantNotFoundException.class);
        assertThatThrownBy(() -> service.get(stranger, plantId))
            .isInstanceOf(PlantNotFoundException.class);
    }

    @Test
    void переименование_и_архивация_только_владельцем() {
        UUID plantId = submittedPlant();

        assertThatThrownBy(() -> service.rename(stranger, plantId, "Взлом"))
            .isInstanceOf(PlantNotFoundException.class); // PENDING скрыт от чужих
        assertThatThrownBy(() -> service.archive(stranger, plantId))
            .isInstanceOf(PlantNotFoundException.class);

        Plant approved = plants.findById(plantId).orElseThrow();
        approved.applyDecision(com.plantarena.plants.domain.ModerationStatus.APPROVED, null);
        plants.save(approved);

        assertThatThrownBy(() -> service.rename(stranger, plantId, "Взлом"))
            .isInstanceOf(AccessDeniedException.class); // видимое, но не своё
        assertThatThrownBy(() -> service.archive(admin, plantId))
            .isInstanceOf(AccessDeniedException.class); // админ не владелец (раздел 13)

        assertThat(service.rename(owner, plantId, "Новое имя").title()).isEqualTo("Новое имя");
    }

    @Test
    void архивация_освобождает_файл_и_запрещена_под_резервом() {
        UUID assetId = assetOf(ownerId, FINGERPRINT);
        UUID plantId = service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetId, "На архив")).id();
        reservations.save(com.plantarena.plants.domain.PlantReservation.reserve(
            ownerId, plantId, new com.plantarena.plants.domain.ImageFingerprint(FINGERPRINT, 1),
            UUID.randomUUID(), NOW));

        assertThatThrownBy(() -> service.archive(owner, plantId))
            .isInstanceOf(PlantUnderReservationException.class);

        reservations.findByIdempotencyKey(reservations.sorted().get(0).idempotencyKey())
            .ifPresent(reservation -> reservations.save(reservation.release(NOW) ? reservation
                : reservation));

        service.archive(owner, plantId);
        assertThat(mediaClaims.claimedAssets).isEmpty(); // файл освобождён
    }

    @Test
    void статус_модерации_только_владельцу() {
        UUID plantId = submittedPlant();

        assertThatThrownBy(() -> service.moderation(stranger, plantId))
            .isInstanceOf(PlantNotFoundException.class);

        var result = service.moderation(owner, plantId);
        assertThat(result.moderationStatus().name()).isEqualTo("PENDING");
        assertThat(result.retryUploadAllowed()).isFalse();
    }
}
```

`src/test/java/com/plantarena/plants/application/PlantModerationServiceTest.java`:

```java
package com.plantarena.plants.application;

import com.plantarena.plants.api.ModerationAlreadyDecidedException;
import com.plantarena.plants.api.PlantModeration;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.event.PlantModerationDecidedEvent;
import com.plantarena.plants.application.support.FakeMediaAssetClaims;
import com.plantarena.plants.application.support.FakeIntegrationEventPublisher;
import com.plantarena.plants.application.support.InMemoryPlantRepository;
import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Контракт PlantModeration: решение командой, идемпотентность, публичность файла")
class PlantModerationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    private final PlantRepository plants = new InMemoryPlantRepository();
    private final FakeMediaAssetClaims mediaClaims = new FakeMediaAssetClaims();
    private final FakeIntegrationEventPublisher events = new FakeIntegrationEventPublisher();
    private final PlantModerationService service =
        new PlantModerationService(plants, mediaClaims, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private UUID submittedPlant(UUID ownerId, UUID assetId) {
        Plant plant = Plant.submit(ownerId, assetId,
            new ImageFingerprint("3".repeat(64), 1), "Фикус", NOW);
        plants.save(plant);
        return plant.id();
    }

    @Test
    void одобрение_фиксирует_решение_делает_файл_публичным_и_публикует_событие() {
        UUID ownerId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId, assetId);

        var result = service.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);

        assertThat(result.moderationStatus().name()).isEqualTo("APPROVED");
        assertThat(mediaClaims.publicAssets).containsExactly(assetId);
        assertThat(events.published).hasSize(1);
        PlantModerationDecidedEvent event = (PlantModerationDecidedEvent) events.published.get(0);
        assertThat(event.eventType()).isEqualTo("PlantModerationDecided");
        assertThat(event.payload().decision()).isEqualTo("APPROVED");
    }

    @Test
    void отклонение_сохраняет_причину_и_не_делает_файл_публичным() {
        UUID assetId = UUID.randomUUID();
        UUID plantId = submittedPlant(UUID.randomUUID(), assetId);

        var result = service.recordDecision(plantId, PlantModeration.Decision.REJECTED, "не растение");

        assertThat(result.moderationStatus().name()).isEqualTo("REJECTED");
        assertThat(mediaClaims.publicAssets).isEmpty();
        assertThat(mediaClaims.claimedAssets).isEmpty(); // claim не вызывался повторно
    }

    @Test
    void повтор_того_же_решения_идемпотентен_без_второго_события() {
        UUID plantId = submittedPlant(UUID.randomUUID(), UUID.randomUUID());
        service.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);

        service.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);

        assertThat(events.published).hasSize(1);
    }

    @Test
    void конфликтующее_решение_отклоняется() {
        UUID plantId = submittedPlant(UUID.randomUUID(), UUID.randomUUID());
        service.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);

        assertThatThrownBy(() ->
            service.recordDecision(plantId, PlantModeration.Decision.REJECTED, "опоздало"))
            .isInstanceOf(ModerationAlreadyDecidedException.class);
    }

    @Test
    void несуществующее_растение_404() {
        assertThatThrownBy(() ->
            service.recordDecision(UUID.randomUUID(), PlantModeration.Decision.APPROVED, null))
            .isInstanceOf(PlantNotFoundException.class);
    }
}
```

`src/test/java/com/plantarena/plants/application/PlantEligibilityServiceTest.java`:

```java
package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantEligibility;
import com.plantarena.plants.api.PlantLifecycle;
import com.plantarena.plants.api.PlantNotEligibleException;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.ReservationConflictException;
import com.plantarena.plants.application.support.FakeIntegrationEventPublisher;
import com.plantarena.plants.application.support.InMemoryImageRestrictionRepository;
import com.plantarena.plants.application.support.InMemoryPlantRepository;
import com.plantarena.plants.application.support.InMemoryPlantReservationRepository;
import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Контракт PlantEligibility: резерв, подтверждение допуска, освобождение")
class PlantEligibilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final ImageFingerprint FINGERPRINT =
        new ImageFingerprint("4".repeat(64), 1);

    private final PlantRepository plants = new InMemoryPlantRepository();
    private final InMemoryImageRestrictionRepository restrictions =
        new InMemoryImageRestrictionRepository();
    private final InMemoryPlantReservationRepository reservations =
        new InMemoryPlantReservationRepository();
    private final PlantEligibilityService service = new PlantEligibilityService(
        plants, restrictions, reservations, Clock.fixed(NOW, ZoneOffset.UTC));
    private final PlantLifecycleService lifecycle = new PlantLifecycleService(
        plants, restrictions, new FakeIntegrationEventPublisher(),
        Clock.fixed(NOW, ZoneOffset.UTC));

    private UUID submittedPlant(UUID ownerId) {
        Plant plant = Plant.submit(ownerId, UUID.randomUUID(), FINGERPRINT, "Фикус", NOW);
        plants.save(plant);
        return plant.id();
    }

    @Test
    void резерв_проверяет_владельца_жизнь_статус_и_запреты() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId);

        assertThatThrownBy(() -> service.reserveSubmission(UUID.randomUUID(), plantId,
            UUID.randomUUID()))
            .isInstanceOf(PlantNotEligibleException.class)
            .extracting("reason")
            .isEqualTo(PlantNotEligibleException.Reason.NOT_OWNER);

        assertThatThrownBy(() -> service.reserveSubmission(ownerId, UUID.randomUUID(),
            UUID.randomUUID()))
            .isInstanceOf(PlantNotFoundException.class);
    }

    @Test
    void отклонённая_заявка_не_резервируется() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId);
        plants.findById(plantId).ifPresent(plant -> {
            plant.applyDecision(ModerationStatus.REJECTED, "не растение");
            plants.save(plant);
        });

        assertThatThrownBy(() -> service.reserveSubmission(ownerId, plantId, UUID.randomUUID()))
            .isInstanceOf(PlantNotEligibleException.class)
            .extracting("reason")
            .isEqualTo(PlantNotEligibleException.Reason.NOT_RESERVABLE);
    }

    @Test
    void запрещённое_изображение_не_резервируется_с_указанием_срока() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId);
        lifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.COOLDOWN,
            NOW.plus(Duration.ofHours(24)), "поражение в глобальном турнире", null);
        UUID secondPlantId = submittedPlant(ownerId); // та же картинка, новое растение

        assertThatThrownBy(() -> service.reserveSubmission(ownerId, secondPlantId,
            UUID.randomUUID()))
            .isInstanceOf(PlantNotEligibleException.class)
            .extracting("reason")
            .isEqualTo(PlantNotEligibleException.Reason.RESTRICTED);
    }

    @Test
    void одно_изображение_владельца_не_участвует_одновременно_в_нескольких_турнирах() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId);
        UUID first = service.reserveSubmission(ownerId, plantId, UUID.randomUUID());

        assertThatThrownBy(() -> service.reserveSubmission(ownerId, plantId, UUID.randomUUID()))
            .isInstanceOf(ReservationConflictException.class); // допущение 4

        UUID retry = service.reserveSubmission(ownerId, plantId, reservations
            .findById(first).orElseThrow().idempotencyKey());
        assertThat(retry).isEqualTo(first); // тот же ключ — тот же reservationId
    }

    @Test
    void подтверждение_допуска_требует_одобрения_и_активный_резерв() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId);
        UUID reservationId = service.reserveSubmission(ownerId, plantId, UUID.randomUUID());

        assertThatThrownBy(() -> service.confirmEligibility(ownerId, plantId, reservationId))
            .isInstanceOf(PlantNotEligibleException.class)
            .extracting("reason")
            .isEqualTo(PlantNotEligibleException.Reason.NOT_APPROVED); // PENDING

        plants.findById(plantId).ifPresent(plant -> {
            plant.applyDecision(ModerationStatus.APPROVED, null);
            plants.save(plant);
        });
        service.confirmEligibility(ownerId, plantId, reservationId); // без исключения

        service.releaseReservation(reservationId);
        assertThatThrownBy(() -> service.confirmEligibility(ownerId, plantId, reservationId))
            .isInstanceOf(PlantNotEligibleException.class)
            .extracting("reason")
            .isEqualTo(PlantNotEligibleException.Reason.NO_ACTIVE_RESERVATION);

        UUID renewed = service.reserveSubmission(ownerId, plantId, UUID.randomUUID());
        assertThat(renewed).isNotEqualTo(reservationId); // после освобождения — новый резерв
    }

    @Test
    void освобождение_идемпотентно() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId);
        UUID reservationId = service.reserveSubmission(ownerId, plantId, UUID.randomUUID());

        service.releaseReservation(reservationId);
        service.releaseReservation(reservationId); // без исключения
        service.releaseReservation(UUID.randomUUID()); // несуществующий — no-op
    }
}
```

`src/test/java/com/plantarena/plants/application/PlantLifecycleServiceTest.java`:

```java
package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantLifecycle;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.event.PlantDiedEvent;
import com.plantarena.plants.application.support.FakeIntegrationEventPublisher;
import com.plantarena.plants.application.support.InMemoryImageRestrictionRepository;
import com.plantarena.plants.application.support.InMemoryPlantRepository;
import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import com.plantarena.plants.domain.RestrictionKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Контракт PlantLifecycle: гибель, запреты, идемпотентность")
class PlantLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final ImageFingerprint FINGERPRINT =
        new ImageFingerprint("5".repeat(64), 1);

    private final PlantRepository plants = new InMemoryPlantRepository();
    private final InMemoryImageRestrictionRepository restrictions =
        new InMemoryImageRestrictionRepository();
    private final FakeIntegrationEventPublisher events = new FakeIntegrationEventPublisher();
    private final PlantLifecycleService service = new PlantLifecycleService(
        plants, restrictions, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private UUID submittedPlant(UUID ownerId) {
        Plant plant = Plant.submit(ownerId, UUID.randomUUID(), FINGERPRINT, "Фикус", NOW);
        plants.save(plant);
        return plant.id();
    }

    @Test
    void погибшее_растение_не_воскресает_повторная_гибель_создаёт_запрет_один_раз() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId);

        service.registerDeath(plantId, PlantLifecycle.RestrictionKind.PERMANENT, null,
            "поражение в закрытом турнире", UUID.randomUUID());
        service.registerDeath(plantId, PlantLifecycle.RestrictionKind.PERMANENT, null,
            "поражение в закрытом турнире", UUID.randomUUID()); // повтор доставки

        assertThat(plants.findById(plantId).orElseThrow().lifeStatus().name()).isEqualTo("DEAD");
        assertThat(restrictions.restrictions).hasSize(1); // дубля запрета нет
        assertThat(events.published).hasSize(1);
        PlantDiedEvent event = (PlantDiedEvent) events.published.get(0);
        assertThat(event.eventType()).isEqualTo("PlantDied");
        assertThat(event.payload().restrictionKind()).isEqualTo("PERMANENT");
    }

    @Test
    void постоянный_запрет_приоритетнее_временного_при_накоплении() {
        UUID ownerId = UUID.randomUUID();
        UUID first = submittedPlant(ownerId);
        UUID second = submittedPlant(ownerId);

        service.registerDeath(first, PlantLifecycle.RestrictionKind.COOLDOWN,
            NOW.plus(Duration.ofHours(24)), "поражение в глобальном турнире", null);
        service.registerDeath(second, PlantLifecycle.RestrictionKind.PERMANENT, null,
            "поражение в закрытом турнире", null);

        assertThat(restrictions.findByOwnerAndFingerprint(ownerId, FINGERPRINT.getFingerprint()))
            .hasSize(2); // история не удаляется
        var active = new com.plantarena.plants.domain.ImageReusePolicy()
            .activeRestriction(restrictions.findByOwnerAndFingerprint(ownerId, FINGERPRINT.value()),
                NOW.plusSeconds(60));
        assertThat(active).isPresent();
        assertThat(active.get().kind()).isEqualTo(RestrictionKind.PERMANENT);
    }

    @Test
    void суточный_запрет_хранит_срок_истечения() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId);

        service.registerDeath(plantId, PlantLifecycle.RestrictionKind.COOLDOWN,
            NOW.plus(Duration.ofHours(24)), "поражение в глобальном турнире", null);

        assertThat(restrictions.restrictions).hasSize(1);
        assertThat(restrictions.restrictions.get(0).expiresAt())
            .isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void несогласованные_аргументы_запрета_отклоняются() {
        UUID plantId = submittedPlant(UUID.randomUUID());

        assertThatThrownBy(() -> service.registerDeath(plantId,
            PlantLifecycle.RestrictionKind.COOLDOWN, null, "без срока", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.registerDeath(plantId,
            PlantLifecycle.RestrictionKind.PERMANENT, NOW.plusSeconds(1), "со сроком", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void несуществующее_растение_404() {
        assertThatThrownBy(() -> service.registerDeath(UUID.randomUUID(),
            PlantLifecycle.RestrictionKind.PERMANENT, null, "ничего", null))
            .isInstanceOf(PlantNotFoundException.class);
    }
}
```

(В `PlantLifecycleServiceTest.постоянный_запрет_приоритетнее_временного_при_накоплении` опечатки быть не должно: вызов — `restrictions.findByOwnerAndFingerprint(ownerId, FINGERPRINT.value())`, строку с `FINGERPRINT.getFingerprint()` удалить; в финальном коде теста использовать только `FINGERPRINT.value()`.)

- [ ] **Step 7: Прогнать application-тесты**

```bash
./mvnw test -Dtest='com.plantarena.plants.*' > /tmp/it3-app.log 2>&1; echo "exit: $?"
grep -E "Tests run" /tmp/it3-app.log | tail -1
```

Ожидание: BUILD SUCCESS — доменные (Step 6 Task 2) + 4 application-класса зелёные. ArchUnit зелёный: `plants.application` зависит только от `plants.api`, `plants.domain`, `shared..` (правило 10.2 соблюдено).

- [ ] **Step 8: Коммит**

```bash
git add src/main/java/com/plantarena/plants/application src/test/java/com/plantarena/plants/application
git commit -m "feat(plants): application — use cases, фасады api, AccessPolicy, фейки, тесты допущений 1–5"
```

---

### Task 4: media.api — задействованность и публичная видимость файлов (upstream для plants, ADR-008)

**Files:**
- Create: `src/main/java/com/plantarena/media/api/MediaAssets.java`
- Create: `src/main/java/com/plantarena/media/api/MediaAssetData.java`
- Create: `src/main/java/com/plantarena/media/api/MediaAssetClaims.java`
- Create: `src/main/java/com/plantarena/media/api/AssetInUseException.java`
- Create: `src/main/java/com/plantarena/media/domain/AssetClaim.java`
- Create: `src/main/java/com/plantarena/media/application/port/out/AssetClaimRepository.java`
- Create: `src/main/java/com/plantarena/media/application/MediaAssetsFacade.java`
- Create: `src/main/java/com/plantarena/media/application/MediaAssetClaimsFacade.java`
- Edit: `src/main/java/com/plantarena/media/application/MediaAssetService.java` (конструктор + `delete` + `download`)
- Edit: `src/main/java/com/plantarena/media/application/MediaAccessPolicy.java` (`requireViewer` с `publiclyVisible`)
- Edit: `src/main/java/com/plantarena/media/adapter/in/web/MediaExceptionHandler.java` (409 `ASSET_IN_USE`)
- Create: `src/main/resources/db/migration/media/V3__asset_claims.sql`
- Create: `src/main/java/com/plantarena/media/adapter/out/persistence/AssetClaimJpaEntity.java`
- Create: `src/main/java/com/plantarena/media/adapter/out/persistence/AssetClaimJpaRepository.java`
- Create: `src/main/java/com/plantarena/media/adapter/out/persistence/JpaAssetClaimRepository.java`
- Create (test): `src/test/java/com/plantarena/media/application/support/InMemoryAssetClaimRepository.java`
- Test: `src/test/java/com/plantarena/media/application/MediaAssetClaimsFacadeTest.java`
- Edit (test): `src/test/java/com/plantarena/media/application/MediaAssetServiceTest.java`
- Test (IT): `src/test/java/com/plantarena/media/adapter/out/persistence/JpaAssetClaimRepositoryIT.java`

**Interfaces:**
- Consumes: `MediaAssetRepository`, `MediaAsset`, `MediaAssetNotFoundException` (итерация 2); паттерн JPA-адаптера и `@DataJpaTest`-IT из итерации 2; `SchemaMigrationConfig` (контекст `media` уже в `CONTEXT_SCHEMAS`); бин `Clock`.
- Produces: опубликованный контракт `media.api` — `MediaAssets.findById → MediaAssetData` (без `storageKey`) и `MediaAssetClaims.claim/release` (команды plants, Customer–Supplier); bean `AssetClaimRepository`; таблица `media.asset_claim`; `AssetInUseException` → HTTP 409 `ASSET_IN_USE` (удаление задействованного файла); `requireViewer(actor, ownerId, publiclyVisible)` — чужой видит файл только публично задействованный (APPROVED-растение). `PlantsApiIT` остаётся красным до Task 6.

Дизайн (ADR-008, пишется в Task 7): задействованность живёт в **media**, а не в plants — media upstream и не может зависеть от plants (иначе цикл, раздел 4.3). plants командует (`claim`/`release` через `media.api`), media хранит. Инвариант «один файл — одно неархивированное растение» держится с двух сторон: PK `asset_claim.asset_id` здесь и частичный уникальный индекс `plant_active_asset_uidx` в plants (Task 5). Публичность файла = его растение APPROVED и не архивировано: plants ставит `publiclyVisible=true` при одобрении и снимает claim при архивации.

- [ ] **Step 1: Опубликованный контракт media.api**

`src/main/java/com/plantarena/media/api/MediaAssets.java`:

```java
package com.plantarena.media.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Опубликованный контракт media для downstream-контекстов (раздел 4.3,
 * Customer–Supplier): метаданные файла без внутренних деталей (storageKey
 * остаётся внутри media). Использует plants (InProcessMediaGateway, Task 6).
 */
public interface MediaAssets {

    Optional<MediaAssetData> findById(UUID assetId);
}
```

`src/main/java/com/plantarena/media/api/MediaAssetData.java`:

```java
package com.plantarena.media.api;

import java.util.UUID;

/**
 * DTO опубликованного контракта: только то, что нужно downstream (plants) —
 * владелец и отпечаток. storageKey и сырой хэш не раскрываются.
 */
public record MediaAssetData(UUID id, UUID ownerId, String fingerprint, int fingerprintVersion) {
}
```

`src/main/java/com/plantarena/media/api/MediaAssetClaims.java`:

```java
package com.plantarena.media.api;

import java.util.UUID;

/**
 * Команды задействованности файлов (ADR-008): plants — единственный
 * командующий контекст (Customer–Supplier). claim идемпотентен для того же
 * растения (upsert публичности по решению модерации), конфликтует с чужим
 * (AssetInUseException); release чужого растения и повтор release — no-op
 * (повторная доставка команды безопасна).
 */
public interface MediaAssetClaims {

    void claim(UUID assetId, UUID plantId, boolean publiclyVisible);

    void release(UUID assetId, UUID plantId);
}
```

`src/main/java/com/plantarena/media/api/AssetInUseException.java`:

```java
package com.plantarena.media.api;

/**
 * Файл задействован растением (ADR-008): удаление файла — HTTP 409
 * ASSET_IN_USE; claim чужим растением — конфликт. Часть опубликованного
 * контракта: plants.adapter переводит в AssetAlreadyClaimedException.
 */
public class AssetInUseException extends RuntimeException {

    public AssetInUseException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: Агрегат AssetClaim и порт хранилища**

`src/main/java/com/plantarena/media/domain/AssetClaim.java`:

```java
package com.plantarena.media.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Задействованность файла растением (ADR-008): один файл — одно
 * неархивированное растение; публичность управляется plants по решению
 * модерации. Живёт в media, потому что media — upstream и не может
 * зависеть от plants (иначе цикл, раздел 4.3).
 */
public record AssetClaim(UUID assetId, UUID plantId, boolean publiclyVisible, Instant claimedAt) {

    public AssetClaim {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(plantId, "plantId");
        Objects.requireNonNull(claimedAt, "claimedAt");
    }

    public static AssetClaim claimed(UUID assetId, UUID plantId, boolean publiclyVisible,
                                     Instant at) {
        return new AssetClaim(assetId, plantId, publiclyVisible, at);
    }
}
```

`src/main/java/com/plantarena/media/application/port/out/AssetClaimRepository.java`:

```java
package com.plantarena.media.application.port.out;

import com.plantarena.media.domain.AssetClaim;
import java.util.Optional;
import java.util.UUID;

/** Порт хранилища задействованности (реализация — JPA, Step 6). */
public interface AssetClaimRepository {

    Optional<AssetClaim> findByAssetId(UUID assetId);

    AssetClaim save(AssetClaim claim);

    void deleteByAssetId(UUID assetId);
}
```

- [ ] **Step 3: Фасады контрактов (application)**

`src/main/java/com/plantarena/media/application/MediaAssetsFacade.java`:

```java
package com.plantarena.media.application;

import com.plantarena.media.api.MediaAssetData;
import com.plantarena.media.api.MediaAssets;
import com.plantarena.media.application.port.out.MediaAssetRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация опубликованного контракта MediaAssets: чтение метаданных
 * без внутренних деталей (storageKey остаётся в media).
 */
@Service
@Transactional(readOnly = true)
public class MediaAssetsFacade implements MediaAssets {

    private final MediaAssetRepository repository;

    public MediaAssetsFacade(MediaAssetRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<MediaAssetData> findById(UUID assetId) {
        return repository.findById(assetId)
            .map(asset -> new MediaAssetData(asset.id(), asset.ownerId(),
                asset.fingerprint().value(), asset.fingerprint().version()));
    }
}
```

`src/main/java/com/plantarena/media/application/MediaAssetClaimsFacade.java`:

```java
package com.plantarena.media.application;

import com.plantarena.media.api.AssetInUseException;
import com.plantarena.media.api.MediaAssetClaims;
import com.plantarena.media.application.port.out.AssetClaimRepository;
import com.plantarena.media.domain.AssetClaim;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация команд задействованности (ADR-008). Один файл — одно
 * неархивированное растение: PK asset_id. Повтор claim того же растения —
 * upsert публичности (модерация APPROVED), чужого — конфликт; release
 * чужого растения и повтор release — no-op (идемпотентность доставки).
 */
@Service
public class MediaAssetClaimsFacade implements MediaAssetClaims {

    private final AssetClaimRepository claims;
    private final Clock clock;

    public MediaAssetClaimsFacade(AssetClaimRepository claims, Clock clock) {
        this.claims = claims;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void claim(UUID assetId, UUID plantId, boolean publiclyVisible) {
        claims.findByAssetId(assetId).ifPresent(existing -> {
            if (!existing.plantId().equals(plantId)) {
                throw new AssetInUseException(
                    "Файл уже задействован растением: " + existing.plantId());
            }
        });
        claims.save(AssetClaim.claimed(assetId, plantId, publiclyVisible, clock.instant()));
    }

    @Override
    @Transactional
    public void release(UUID assetId, UUID plantId) {
        claims.findByAssetId(assetId)
            .filter(claim -> claim.plantId().equals(plantId))
            .ifPresent(claim -> claims.deleteByAssetId(assetId));
    }
}
```

- [ ] **Step 4: Интеграция задействованности в use cases media (полные обновлённые файлы)**

`src/main/java/com/plantarena/media/application/MediaAssetService.java` — конструктор получает `AssetClaimRepository`; `delete` запрещён для задействованного файла (409); `download` учитывает публичность:

```java
package com.plantarena.media.application;

import com.plantarena.media.api.AssetInUseException;
import com.plantarena.media.application.port.in.DeleteMediaUseCase;
import com.plantarena.media.application.port.in.DownloadMediaUseCase;
import com.plantarena.media.application.port.in.UploadMediaUseCase;
import com.plantarena.media.application.port.out.AssetClaimRepository;
import com.plantarena.media.application.port.out.MediaAssetRepository;
import com.plantarena.media.domain.AnalyzedImage;
import com.plantarena.media.domain.AssetClaim;
import com.plantarena.media.domain.FileStorage;
import com.plantarena.media.domain.ImageAnalyzer;
import com.plantarena.media.domain.ImageFingerprint;
import com.plantarena.media.domain.ImageFingerprinter;
import com.plantarena.media.domain.MediaAsset;
import com.plantarena.shared.security.CurrentActor;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Use cases media (раздел 6). Транзакции по разделу 12: файл сохраняется
 * в хранилище ДО короткой транзакции регистрации метаданных (в адаптере);
 * сбой регистрации компенсируется удалением сиротского файла.
 * Задействованность (ADR-008): удаление занятого файла — 409 ASSET_IN_USE;
 * скачивание чужих — только публичная задействованность (APPROVED-растение).
 */
@Service
public class MediaAssetService implements UploadMediaUseCase, DownloadMediaUseCase, DeleteMediaUseCase {

    static final long MAX_BYTES = 10 * 1024 * 1024; // 10 MiB (раздел 6)

    private final MediaAssetRepository repository;
    private final ImageAnalyzer imageAnalyzer;
    private final FileStorage fileStorage;
    private final AssetClaimRepository claims;
    private final MediaAccessPolicy accessPolicy;
    private final Clock clock;
    private final ImageFingerprinter fingerprinter = new ImageFingerprinter();

    public MediaAssetService(MediaAssetRepository repository, ImageAnalyzer imageAnalyzer,
                             FileStorage fileStorage, AssetClaimRepository claims,
                             MediaAccessPolicy accessPolicy, Clock clock) {
        this.repository = repository;
        this.imageAnalyzer = imageAnalyzer;
        this.fileStorage = fileStorage;
        this.claims = claims;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    @Override
    public MediaAssetResult upload(CurrentActor actor, byte[] content) {
        accessPolicy.requireUploader(actor);
        if (content.length > MAX_BYTES) {
            throw new FileTooLargeException("Файл превышает 10 MiB: " + content.length + " байт");
        }
        AnalyzedImage image = imageAnalyzer.analyze(content); // 415 / 413 (пиксели)
        ImageFingerprint fingerprint = fingerprinter.fingerprint(image);
        String rawSha256 = fingerprinter.rawSha256(content);
        String storageKey = fileStorage.save(content, image.format());
        try {
            MediaAsset asset = MediaAsset.uploaded(actor.userId(), storageKey, image.format(),
                content.length, image.width(), image.height(), rawSha256, fingerprint,
                clock.instant());
            repository.save(asset); // короткая транзакция (раздел 12)
            return MediaAssetResult.from(asset);
        } catch (RuntimeException e) {
            fileStorage.delete(storageKey); // компенсация сиротского файла
            throw e;
        }
    }

    @Override
    public DownloadedMedia download(CurrentActor actor, UUID assetId) {
        MediaAsset asset = find(assetId);
        boolean publiclyVisible = claims.findByAssetId(asset.id())
            .map(AssetClaim::publiclyVisible)
            .orElse(false);
        accessPolicy.requireViewer(actor, asset.ownerId(), publiclyVisible);
        return new DownloadedMedia(asset.id(), asset.format().mimeType(),
            fileStorage.read(asset.storageKey()));
    }

    @Override
    public void delete(CurrentActor actor, UUID assetId) {
        MediaAsset asset = find(assetId);
        accessPolicy.requireDeleter(actor, asset.ownerId());
        claims.findByAssetId(asset.id()).ifPresent(claim -> {
            throw new AssetInUseException( // 409: сначала архивируйте растение (ADR-008)
                "Файл задействован растением: " + claim.plantId());
        });
        repository.delete(asset.id());           // короткая транзакция
        fileStorage.delete(asset.storageKey());  // метаданные уже удалены — «висячих» ссылок нет
    }

    private MediaAsset find(UUID assetId) {
        return repository.findById(assetId)
            .orElseThrow(() -> new MediaAssetNotFoundException("Файл не найден: " + assetId));
    }
}
```

`src/main/java/com/plantarena/media/application/MediaAccessPolicy.java` — `requireViewer` получает `publiclyVisible`:

```java
package com.plantarena.media.application;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Права доступа media (раздел 13 + ADR-008): загрузка — любой
 * идентифицированный пользователь; скачивание — владелец всегда, чужие —
 * только публично задействованный файл (APPROVED-растение, иначе 404);
 * удаление — владелец или админ (чужим — 403), но не задействованный файл.
 */
@Component
public class MediaAccessPolicy {

    public void requireUploader(CurrentActor actor) {
        if (actor.isGuest()) {
            throw new NotIdentifiedException(
                "Загрузка файлов доступна только идентифицированным пользователям");
        }
    }

    public void requireViewer(CurrentActor actor, UUID ownerId, boolean publiclyVisible) {
        if (actor.isGuest()) {
            throw new NotIdentifiedException(
                "Скачивание файлов доступно только идентифицированным пользователям");
        }
        if (!actor.userId().equals(ownerId) && !publiclyVisible) {
            throw new MediaAssetNotFoundException("Файл не найден"); // скрыт приватностью
        }
    }

    public void requireDeleter(CurrentActor actor, UUID ownerId) {
        if (actor.isGuest()) {
            throw new NotIdentifiedException(
                "Удаление файлов доступно только идентифицированным пользователям");
        }
        if (!actor.userId().equals(ownerId) && !actor.hasRole(AppRole.ADMIN)) {
            throw new AccessDeniedException("Удалить файл может владелец или администратор");
        }
    }
}
```

`src/main/java/com/plantarena/media/adapter/in/web/MediaExceptionHandler.java` — добавить импорт и обработчик (остальное без изменений):

```java
import com.plantarena.media.api.AssetInUseException;
```

```java
    /** 409: файл задействован растением — сначала архивируйте растение (ADR-008). */
    @ExceptionHandler(AssetInUseException.class)
    public ResponseEntity<ApiError> inUse(AssetInUseException e, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "ASSET_IN_USE", e.getMessage(), request);
    }
```

- [ ] **Step 5: Миграция media V3**

`src/main/resources/db/migration/media/V3__asset_claims.sql`:

```sql
-- Контекст media: задействованность файлов растениями (ADR-008, итерация 3).
-- Задействованность живёт в media (upstream), потому что media не может
-- зависеть от plants (раздел 4.3, иначе цикл): plants командует через
-- MediaAssetClaims, media хранит. Один файл — одно растение: PK по asset_id.
-- Инвариант «одно неархивированное растение на файл» дублируется на стороне
-- plants частичным уникальным индексом plant_active_asset_uidx (V2 plants).
-- Запись перезаписывается upsert-ом фасада — version (optimistic locking) не нужен.
-- FK ON DELETE CASCADE: удаление asset'а убирает и задействованность.
CREATE TABLE asset_claim (
    asset_id         UUID PRIMARY KEY REFERENCES media_asset (id) ON DELETE CASCADE,
    plant_id         UUID        NOT NULL,
    publicly_visible BOOLEAN     NOT NULL,
    claimed_at       TIMESTAMPTZ NOT NULL
);
```

- [ ] **Step 6: JPA-адаптер**

`src/main/java/com/plantarena/media/adapter/out/persistence/AssetClaimJpaEntity.java`:

```java
package com.plantarena.media.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA-модель asset_claim (ADR-008). PK — asset_id: один файл — одно
 * задействованное растение; запись перезаписывается upsert-ом фасада,
 * поэтому без version (optimistic locking не нужен).
 */
@Entity
@Table(name = "asset_claim", schema = "media")
public class AssetClaimJpaEntity {

    @Id
    private UUID assetId;

    @Column(name = "plant_id", nullable = false)
    private UUID plantId;

    @Column(name = "publicly_visible", nullable = false)
    private boolean publiclyVisible;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;

    protected AssetClaimJpaEntity() {
    }

    AssetClaimJpaEntity(UUID assetId, UUID plantId, boolean publiclyVisible, Instant claimedAt) {
        this.assetId = assetId;
        this.plantId = plantId;
        this.publiclyVisible = publiclyVisible;
        this.claimedAt = claimedAt;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public UUID getPlantId() {
        return plantId;
    }

    public boolean isPubliclyVisible() {
        return publiclyVisible;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }
}
```

`src/main/java/com/plantarena/media/adapter/out/persistence/AssetClaimJpaRepository.java`:

```java
package com.plantarena.media.adapter.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data для asset_claim; PK — asset_id (один файл — одно растение). */
public interface AssetClaimJpaRepository extends JpaRepository<AssetClaimJpaEntity, UUID> {
}
```

`src/main/java/com/plantarena/media/adapter/out/persistence/JpaAssetClaimRepository.java`:

```java
package com.plantarena.media.adapter.out.persistence;

import com.plantarena.media.application.port.out.AssetClaimRepository;
import com.plantarena.media.domain.AssetClaim;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация порта AssetClaimRepository на JPA + PostgreSQL (раздел 14.2).
 * save с существующим asset_id — merge (upsert публичности по решению
 * модерации); с новым — insert.
 */
@Repository
@Transactional
public class JpaAssetClaimRepository implements AssetClaimRepository {

    private final AssetClaimJpaRepository jpaRepository;

    public JpaAssetClaimRepository(AssetClaimJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AssetClaim> findByAssetId(UUID assetId) {
        return jpaRepository.findById(assetId).map(JpaAssetClaimRepository::toDomain);
    }

    @Override
    public AssetClaim save(AssetClaim claim) {
        return toDomain(jpaRepository.saveAndFlush(new AssetClaimJpaEntity(
            claim.assetId(), claim.plantId(), claim.publiclyVisible(), claim.claimedAt())));
    }

    @Override
    public void deleteByAssetId(UUID assetId) {
        jpaRepository.deleteById(assetId);
    }

    private static AssetClaim toDomain(AssetClaimJpaEntity entity) {
        return AssetClaim.claimed(entity.getAssetId(), entity.getPlantId(),
            entity.isPubliclyVisible(), entity.getClaimedAt());
    }
}
```

- [ ] **Step 7: Тесты**

`src/test/java/com/plantarena/media/application/support/InMemoryAssetClaimRepository.java`:

```java
package com.plantarena.media.application.support;

import com.plantarena.media.application.port.out.AssetClaimRepository;
import com.plantarena.media.domain.AssetClaim;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory репозиторий задействованности для application-тестов. */
public class InMemoryAssetClaimRepository implements AssetClaimRepository {

    public final Map<UUID, AssetClaim> claims = new ConcurrentHashMap<>();

    @Override
    public Optional<AssetClaim> findByAssetId(UUID assetId) {
        return Optional.ofNullable(claims.get(assetId));
    }

    @Override
    public AssetClaim save(AssetClaim claim) {
        claims.put(claim.assetId(), claim);
        return claim;
    }

    @Override
    public void deleteByAssetId(UUID assetId) {
        claims.remove(assetId);
    }
}
```

`src/test/java/com/plantarena/media/application/MediaAssetClaimsFacadeTest.java`:

```java
package com.plantarena.media.application;

import com.plantarena.media.api.AssetInUseException;
import com.plantarena.media.application.support.InMemoryAssetClaimRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Контракт MediaAssetClaims: задействованность и публичность файлов")
class MediaAssetClaimsFacadeTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    private final InMemoryAssetClaimRepository claims = new InMemoryAssetClaimRepository();
    private final MediaAssetClaimsFacade facade =
        new MediaAssetClaimsFacade(claims, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void claim_занимает_файл_и_повтор_того_же_растения_обновляет_публичность() {
        UUID assetId = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();

        facade.claim(assetId, plantId, false);   // подача: занят, но не публичен
        facade.claim(assetId, plantId, true);    // модерация APPROVED

        assertThat(claims.claims).hasSize(1);    // upsert, не вторая запись
        assertThat(claims.claims.get(assetId).plantId()).isEqualTo(plantId);
        assertThat(claims.claims.get(assetId).publiclyVisible()).isTrue();
        assertThat(claims.claims.get(assetId).claimedAt()).isEqualTo(NOW);
    }

    @Test
    void claim_файла_чужого_растения_конфликтует() {
        UUID assetId = UUID.randomUUID();
        facade.claim(assetId, UUID.randomUUID(), false);

        assertThatThrownBy(() -> facade.claim(assetId, UUID.randomUUID(), false))
            .isInstanceOf(AssetInUseException.class);
    }

    @Test
    void release_освобождает_файл_и_игнорирует_чужое_растение() {
        UUID assetId = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        facade.claim(assetId, plantId, true);

        facade.release(assetId, UUID.randomUUID()); // чужое растение — no-op
        assertThat(claims.claims).hasSize(1);

        facade.release(assetId, plantId);
        assertThatCode(() -> facade.release(assetId, plantId)) // повтор доставки — no-op
            .doesNotThrowAnyException();
        assertThat(claims.claims).isEmpty();
    }
}
```

`src/test/java/com/plantarena/media/application/MediaAssetServiceTest.java` — обновить конструктор (новый аргумент `claims`), добавить импорты и два теста. Импорты (к существующим):

```java
import com.plantarena.media.api.AssetInUseException;
import com.plantarena.media.application.support.InMemoryAssetClaimRepository;
import com.plantarena.media.domain.AssetClaim;
import java.time.Instant;
```

Поле-инициализация (заменить блок полей `repository`/`storage`/`analyzer`/`service`):

```java
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    private final InMemoryMediaAssetRepository repository = new InMemoryMediaAssetRepository();
    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final InMemoryAssetClaimRepository claims = new InMemoryAssetClaimRepository();
    private final FakeImageAnalyzer analyzer = new FakeImageAnalyzer(
        new AnalyzedImage(ImageFormat.PNG, 2, 1, new int[] {0xFF000000, 0xFF112233}));
    private final MediaAssetService service =
        new MediaAssetService(repository, analyzer, storage, claims,
            new MediaAccessPolicy(), Clock.systemUTC());
```

Новые тесты (добавить в класс):

```java
    @Test
    void удаление_задействованного_файла_запрещено() {
        MediaAssetResult uploaded = service.upload(owner, new byte[] {1, 2, 3});
        claims.save(AssetClaim.claimed(uploaded.id(), UUID.randomUUID(), false, NOW));

        assertThatThrownBy(() -> service.delete(owner, uploaded.id()))
            .isInstanceOf(AssetInUseException.class);
        assertThat(repository.assets).hasSize(1);   // файл не удалён
        assertThat(storage.deletedKeys).isEmpty();
    }

    @Test
    void чужой_видит_файл_только_публично_задействованный() {
        MediaAssetResult uploaded = service.upload(owner, new byte[] {1, 2, 3});
        UUID plantId = UUID.randomUUID();

        claims.save(AssetClaim.claimed(uploaded.id(), plantId, false, NOW));
        assertThatThrownBy(() -> service.download(other, uploaded.id()))
            .isInstanceOf(MediaAssetNotFoundException.class); // занят, но не публичен

        claims.save(AssetClaim.claimed(uploaded.id(), plantId, true, NOW));
        var downloaded = service.download(other, uploaded.id());
        assertThat(downloaded.assetId()).isEqualTo(uploaded.id()); // APPROVED-растение
    }
```

Существующие тесты не меняются: без задействованности `download`/`delete` ведут себя как прежде (владелец видит, чужим 404, удаление свободного файла разрешено).

`src/test/java/com/plantarena/media/adapter/out/persistence/JpaAssetClaimRepositoryIT.java`:

```java
package com.plantarena.media.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.media.domain.AssetClaim;
import com.plantarena.support.PostgresSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт AssetClaimRepository на JPA + PostgreSQL (Testcontainers, раздел 14.2).
 * Миграции выполняет SchemaMigrationConfig (ADR-003), H2 не используется.
 * Контейнер singleton на JVM: перед каждым тестом таблицы чистятся
 * (внутри откатываемой транзакции @DataJpaTest).
 */
@DisplayName("Контракт AssetClaimRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaAssetClaimRepository.class})
class JpaAssetClaimRepositoryIT {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaAssetClaimRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_задействованность_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from media.asset_claim");
        jdbcTemplate.update("delete from media.media_asset");
    }

    private UUID insertedAsset() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            insert into media.media_asset (id, owner_id, storage_key, mime_type, byte_size,
                width, height, raw_sha256, image_fingerprint, fingerprint_version, created_at)
            values (?, ?, ?, 'image/png', 3, 2, 1, ?, ?, 1, now())
            """, id, UUID.randomUUID(), "claim-it-" + id + ".png",
            "a".repeat(64), "b".repeat(64));
        return id;
    }

    @Test
    @DisplayName("задействованность сохраняется, находится по файлу и удаляется")
    void задействованность_сохраняется_находится_по_файлу_и_удаляется() {
        UUID assetId = insertedAsset();
        UUID plantId = UUID.randomUUID();
        repository.save(AssetClaim.claimed(assetId, plantId, true,
            Instant.parse("2026-09-25T10:00:00Z")));

        var found = repository.findByAssetId(assetId);

        assertThat(found).isPresent();
        assertThat(found.get().plantId()).isEqualTo(plantId);
        assertThat(found.get().publiclyVisible()).isTrue();

        repository.deleteByAssetId(assetId);
        assertThat(repository.findByAssetId(assetId)).isEmpty();
    }

    @Test
    @DisplayName("повторный claim того же файла перезаписывает задействованность (PK asset_id)")
    void повторный_claim_того_же_файла_перезаписывает_задействованность() {
        UUID assetId = insertedAsset();
        repository.save(AssetClaim.claimed(assetId, UUID.randomUUID(), false,
            Instant.parse("2026-09-25T10:00:00Z")));

        repository.save(AssetClaim.claimed(assetId, UUID.randomUUID(), true,
            Instant.parse("2026-09-25T11:00:00Z"))); // merge → update по PK

        var found = repository.findByAssetId(assetId).orElseThrow();
        assertThat(found.publiclyVisible()).isTrue();
    }
}
```

- [ ] **Step 8: Прогнать тесты media**

```bash
./mvnw test -Dtest='com.plantarena.media.application.*' > /tmp/it3-media-unit.log 2>&1; echo "exit: $?"
grep -E "Tests run" /tmp/it3-media-unit.log | tail -1
```

Ожидание: BUILD SUCCESS — `MediaAssetServiceTest` (12: 10 существующих + 2 новых), `MediaAssetClaimsFacadeTest` (3) зелёные.

```bash
./mvnw verify -Dtest='com.plantarena.media.application.*' -Dit.test='JpaAssetClaimRepositoryIT' > /tmp/it3-media-it.log 2>&1; echo "exit: $?"
grep -E "Tests run" /tmp/it3-media-it.log | tail -1
```

Ожидание: BUILD SUCCESS — `JpaAssetClaimRepositoryIT` (2) зелёный (Testcontainers). ArchUnit зелёный: `media.api` не зависит от `media.domain`/`media.adapter` (apiIsolated); `MediaExceptionHandler` → `media.api` разрешено (правило запрещает adapter.in.web только domain и adapter.out.persistence). `PlantsApiIT` не запускался (фильтр `-Dit.test`) и остаётся красным до Task 6 — ожидаемо.

- [ ] **Step 9: Коммит**

```bash
git add src/main/java/com/plantarena/media src/main/resources/db/migration/media src/test/java/com/plantarena/media
git commit -m "feat(media): задействованность и публичная видимость файлов — контракт media.api для plants (ADR-008)"
```

---

### Task 5: Миграция plants V2, JPA-адаптеры, контрактные тесты репозиториев

**Files:**
- Create: `src/main/resources/db/migration/plants/V2__plants.sql`
- Create: `src/main/java/com/plantarena/plants/adapter/out/persistence/PlantJpaEntity.java`
- Create: `src/main/java/com/plantarena/plants/adapter/out/persistence/ImageRestrictionJpaEntity.java`
- Create: `src/main/java/com/plantarena/plants/adapter/out/persistence/PlantReservationJpaEntity.java`
- Create: `src/main/java/com/plantarena/plants/adapter/out/persistence/PlantJpaRepository.java`
- Create: `src/main/java/com/plantarena/plants/adapter/out/persistence/ImageRestrictionJpaRepository.java`
- Create: `src/main/java/com/plantarena/plants/adapter/out/persistence/PlantReservationJpaRepository.java`
- Create: `src/main/java/com/plantarena/plants/adapter/out/persistence/JpaPlantRepository.java`
- Create: `src/main/java/com/plantarena/plants/adapter/out/persistence/JpaImageRestrictionRepository.java`
- Create: `src/main/java/com/plantarena/plants/adapter/out/persistence/JpaPlantReservationRepository.java`
- Test (контрактная база): `src/test/java/com/plantarena/plants/PlantRepositoryContractTest.java`
- Test (контрактная база): `src/test/java/com/plantarena/plants/ImageRestrictionRepositoryContractTest.java`
- Test (контрактная база): `src/test/java/com/plantarena/plants/PlantReservationRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/plants/application/support/InMemoryPlantRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/plants/application/support/InMemoryImageRestrictionRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/plants/application/support/InMemoryPlantReservationRepositoryContractTest.java`
- Test (IT): `src/test/java/com/plantarena/plants/adapter/out/persistence/JpaPlantRepositoryContractIT.java`
- Test (IT): `src/test/java/com/plantarena/plants/adapter/out/persistence/JpaImageRestrictionRepositoryContractIT.java`
- Test (IT): `src/test/java/com/plantarena/plants/adapter/out/persistence/JpaPlantReservationRepositoryContractIT.java`

**Interfaces:**
- Consumes: домен Task 2 (`Plant.restore`, `ImageRestriction.restore`, `PlantReservation.restore`, порты), `SchemaMigrationConfig` (контекст `plants` уже в `CONTEXT_SCHEMAS`, каталог `db/migration/plants` с V1-заглушкой), паттерн контрактных тестов `MediaAssetRepositoryContractTest`/`JpaMediaAssetRepositoryContractIT` из итерации 2, паттерн find-or-create + update + saveAndFlush из `JpaUserRepository`.
- Produces: beans `PlantRepository`, `ImageRestrictionRepository`, `PlantReservationRepository` (JPA) — закрывают репозиторные зависимости сервисов Task 3; таблицы `plants.plant`, `plants.image_restriction`, `plants.plant_reservation` с частичными уникальными индексами `plant_active_asset_uidx` (ADR-008) и `plant_reservation_active_pair_uidx` (допущение 4) — для `PlantsApiIT` (Task 6) и последующих итераций.

**Критический урок итерации 2 (фикс 56e8e3a):** `@Transactional` обязателен на самих абстрактных контрактных базах — аннотация `@DataJpaTest` на подклассе НЕ применяется к наследуемым тест-методам (Spring ищет аннотацию от метода вверх по классу-объявителю), иначе JPA-наследники автокоммитят и протекают в общую Testcontainers-БД. Все три базы ниже аннотированы.

- [ ] **Step 1: Миграция plants V2**

`src/main/resources/db/migration/plants/V2__plants.sql`:

```sql
-- Контекст plants: растения, запреты изображений, резервы (разделы 5, 6 и 11).
-- Enum → VARCHAR + CHECK. Plant мутирует (модерация/жизнь/архив) — version
-- (optimistic locking); запреты append-only и резервы с единственным переходом
-- ACTIVE→RELEASED — без version. asset_id/owner_id/plant_id без FK:
-- контексты выделяются в сервисы (раздел 10.4), cross-schema FK связал бы их навсегда.
CREATE TABLE plant (
    id                  UUID PRIMARY KEY,
    owner_id            UUID         NOT NULL,
    asset_id            UUID         NOT NULL,
    fingerprint         VARCHAR(64)  NOT NULL,
    fingerprint_version INTEGER      NOT NULL,
    title               VARCHAR(100) NOT NULL,
    moderation_status   VARCHAR(10)  NOT NULL CHECK (moderation_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    moderation_reason   VARCHAR(255),
    life_status         VARCHAR(10)  NOT NULL CHECK (life_status IN ('ALIVE', 'DEAD')),
    died_at             TIMESTAMPTZ,
    archived_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0
);

-- Один файл — одно неархивированное растение (ADR-008): частичный уникальный
-- индекс; архивация освобождает файл для нового растения.
CREATE UNIQUE INDEX plant_active_asset_uidx ON plant (asset_id) WHERE archived_at IS NULL;
CREATE INDEX plant_owner_idx ON plant (owner_id, created_at);

CREATE TABLE image_restriction (
    id                  UUID PRIMARY KEY,
    owner_id            UUID         NOT NULL,
    fingerprint         VARCHAR(64)  NOT NULL,
    fingerprint_version INTEGER      NOT NULL,
    kind                VARCHAR(10)  NOT NULL CHECK (kind IN ('PERMANENT', 'COOLDOWN')),
    expires_at          TIMESTAMPTZ, -- NULL только для PERMANENT
    reason              VARCHAR(255) NOT NULL,
    source_entry_id     UUID,
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT image_restriction_kind_expires_chk CHECK (
        (kind = 'PERMANENT' AND expires_at IS NULL)
        OR (kind = 'COOLDOWN' AND expires_at IS NOT NULL)
    )
);
CREATE INDEX image_restriction_owner_fingerprint_idx
    ON image_restriction (owner_id, fingerprint);

CREATE TABLE plant_reservation (
    id                  UUID PRIMARY KEY,
    owner_id            UUID         NOT NULL,
    plant_id            UUID         NOT NULL,
    fingerprint         VARCHAR(64)  NOT NULL,
    fingerprint_version INTEGER      NOT NULL,
    idempotency_key     UUID         NOT NULL UNIQUE,
    status              VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'RELEASED')),
    created_at          TIMESTAMPTZ  NOT NULL,
    released_at         TIMESTAMPTZ,
    CONSTRAINT plant_reservation_status_released_chk CHECK (
        (status = 'ACTIVE' AND released_at IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL)
    )
);
-- Допущение 4: не более одного активного резерва на пару (owner_id, fingerprint).
CREATE UNIQUE INDEX plant_reservation_active_pair_uidx
    ON plant_reservation (owner_id, fingerprint) WHERE status = 'ACTIVE';
```

- [ ] **Step 2: JPA-сущности**

`src/main/java/com/plantarena/plants/adapter/out/persistence/PlantJpaEntity.java`:

```java
package com.plantarena.plants.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA-модель plant (раздел 11); маппинг на домен — явный (в JpaPlantRepository).
 * Plant мутирует (модерация/жизнь/архив) — optimistic locking через @Version.
 */
@Entity
@Table(name = "plant", schema = "plants")
public class PlantJpaEntity {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "fingerprint_version", nullable = false)
    private int fingerprintVersion;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "moderation_status", nullable = false)
    private String moderationStatus;

    @Column(name = "moderation_reason")
    private String moderationReason;

    @Column(name = "life_status", nullable = false)
    private String lifeStatus;

    @Column(name = "died_at")
    private Instant diedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PlantJpaEntity() {
    }

    PlantJpaEntity(UUID id, UUID ownerId, UUID assetId, String fingerprint,
                   int fingerprintVersion, String title, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.assetId = assetId;
        this.fingerprint = fingerprint;
        this.fingerprintVersion = fingerprintVersion;
        this.title = title;
        this.createdAt = createdAt;
    }

    /** Мутации домена; id/owner/asset/fingerprint/created_at неизменяемы. */
    void update(String title, String moderationStatus, String moderationReason,
                String lifeStatus, Instant diedAt, Instant archivedAt) {
        this.title = title;
        this.moderationStatus = moderationStatus;
        this.moderationReason = moderationReason;
        this.lifeStatus = lifeStatus;
        this.diedAt = diedAt;
        this.archivedAt = archivedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public int getFingerprintVersion() {
        return fingerprintVersion;
    }

    public String getTitle() {
        return title;
    }

    public String getModerationStatus() {
        return moderationStatus;
    }

    public String getModerationReason() {
        return moderationReason;
    }

    public String getLifeStatus() {
        return lifeStatus;
    }

    public Instant getDiedAt() {
        return diedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
```

`src/main/java/com/plantarena/plants/adapter/out/persistence/ImageRestrictionJpaEntity.java`:

```java
package com.plantarena.plants.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA-модель image_restriction (раздел 11). Append-only: только создаётся
 * и читается — version (optimistic locking) не нужен.
 */
@Entity
@Table(name = "image_restriction", schema = "plants")
public class ImageRestrictionJpaEntity {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "fingerprint_version", nullable = false)
    private int fingerprintVersion;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "source_entry_id")
    private UUID sourceEntryId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ImageRestrictionJpaEntity() {
    }

    ImageRestrictionJpaEntity(UUID id, UUID ownerId, String fingerprint, int fingerprintVersion,
                              String kind, Instant expiresAt, String reason,
                              UUID sourceEntryId, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.fingerprint = fingerprint;
        this.fingerprintVersion = fingerprintVersion;
        this.kind = kind;
        this.expiresAt = expiresAt;
        this.reason = reason;
        this.sourceEntryId = sourceEntryId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public int getFingerprintVersion() {
        return fingerprintVersion;
    }

    public String getKind() {
        return kind;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getReason() {
        return reason;
    }

    public UUID getSourceEntryId() {
        return sourceEntryId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

`src/main/java/com/plantarena/plants/adapter/out/persistence/PlantReservationJpaEntity.java`:

```java
package com.plantarena.plants.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA-модель plant_reservation (раздел 11). Единственный переход
 * ACTIVE→RELEASED идемпотентен (повтор release пишет то же состояние) —
 * version (optimistic locking) не нужен.
 */
@Entity
@Table(name = "plant_reservation", schema = "plants")
public class PlantReservationJpaEntity {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "plant_id", nullable = false)
    private UUID plantId;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "fingerprint_version", nullable = false)
    private int fingerprintVersion;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected PlantReservationJpaEntity() {
    }

    PlantReservationJpaEntity(UUID id, UUID ownerId, UUID plantId, String fingerprint,
                              int fingerprintVersion, UUID idempotencyKey, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.plantId = plantId;
        this.fingerprint = fingerprint;
        this.fingerprintVersion = fingerprintVersion;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }

    /** Единственная мутация: ACTIVE → RELEASED. */
    void update(String status, Instant releasedAt) {
        this.status = status;
        this.releasedAt = releasedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getPlantId() {
        return plantId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public int getFingerprintVersion() {
        return fingerprintVersion;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}
```

- [ ] **Step 3: Spring Data репозитории**

`src/main/java/com/plantarena/plants/adapter/out/persistence/PlantJpaRepository.java`:

```java
package com.plantarena.plants.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data для plant; «активное» = не архивировано (ADR-008). */
public interface PlantJpaRepository extends JpaRepository<PlantJpaEntity, UUID> {

    Optional<PlantJpaEntity> findByAssetIdAndArchivedAtIsNull(UUID assetId);

    List<PlantJpaEntity> findByOwnerIdAndArchivedAtIsNull(UUID ownerId, Pageable pageable);

    long countByOwnerIdAndArchivedAtIsNull(UUID ownerId);

    List<PlantJpaEntity> findByOwnerIdAndArchivedAtIsNullAndModerationStatus(
        UUID ownerId, String moderationStatus, Pageable pageable);

    long countByOwnerIdAndArchivedAtIsNullAndModerationStatus(
        UUID ownerId, String moderationStatus);
}
```

`src/main/java/com/plantarena/plants/adapter/out/persistence/ImageRestrictionJpaRepository.java`:

```java
package com.plantarena.plants.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data для image_restriction: append-only, поиск по паре. */
public interface ImageRestrictionJpaRepository extends JpaRepository<ImageRestrictionJpaEntity, UUID> {

    List<ImageRestrictionJpaEntity> findByOwnerIdAndFingerprint(UUID ownerId, String fingerprint);
}
```

`src/main/java/com/plantarena/plants/adapter/out/persistence/PlantReservationJpaRepository.java`:

```java
package com.plantarena.plants.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data для plant_reservation; findFirst — страховка от дублей. */
public interface PlantReservationJpaRepository extends JpaRepository<PlantReservationJpaEntity, UUID> {

    Optional<PlantReservationJpaEntity> findByIdempotencyKey(UUID idempotencyKey);

    Optional<PlantReservationJpaEntity> findFirstByOwnerIdAndFingerprintAndStatus(
        UUID ownerId, String fingerprint, String status);

    Optional<PlantReservationJpaEntity> findFirstByPlantIdAndStatus(UUID plantId, String status);
}
```

- [ ] **Step 4: Реализации портов**

`src/main/java/com/plantarena/plants/adapter/out/persistence/JpaPlantRepository.java`:

```java
package com.plantarena.plants.adapter.out.persistence;

import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.LifeStatus;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация порта PlantRepository на JPA + PostgreSQL (раздел 14.2).
 * find-or-create + update + saveAndFlush (паттерн JpaUserRepository):
 * конкурентные обновления ловит @Version в БД. Сортировка по id —
 * детерминированная пагинация с tie-break (раздел 13).
 */
@Repository
@Transactional
public class JpaPlantRepository implements PlantRepository {

    private final PlantJpaRepository plants;

    public JpaPlantRepository(PlantJpaRepository plants) {
        this.plants = plants;
    }

    @Override
    public Plant save(Plant plant) {
        PlantJpaEntity entity = plants.findById(plant.id())
            .orElseGet(() -> new PlantJpaEntity(plant.id(), plant.ownerId(), plant.assetId(),
                plant.fingerprint().value(), plant.fingerprint().algorithmVersion(),
                plant.title(), plant.createdAt()));
        entity.update(plant.title(), plant.moderationStatus().name(), plant.moderationReason(),
            plant.lifeStatus().name(), plant.diedAt(), plant.archivedAt());
        return toDomain(plants.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Plant> findById(UUID id) {
        return plants.findById(id).map(JpaPlantRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Plant> findActiveByAssetId(UUID assetId) {
        return plants.findByAssetIdAndArchivedAtIsNull(assetId)
            .map(JpaPlantRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Plant> findByOwner(UUID ownerId, int offset, int limit) {
        // offset всегда page-aligned (page * size из use case, как в JpaUserRepository)
        return plants.findByOwnerIdAndArchivedAtIsNull(ownerId, page(offset, limit)).stream()
            .map(JpaPlantRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByOwner(UUID ownerId) {
        return plants.countByOwnerIdAndArchivedAtIsNull(ownerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Plant> findApprovedByOwner(UUID ownerId, int offset, int limit) {
        return plants.findByOwnerIdAndArchivedAtIsNullAndModerationStatus(ownerId,
                ModerationStatus.APPROVED.name(), page(offset, limit)).stream()
            .map(JpaPlantRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countApprovedByOwner(UUID ownerId) {
        return plants.countByOwnerIdAndArchivedAtIsNullAndModerationStatus(ownerId,
            ModerationStatus.APPROVED.name());
    }

    private static PageRequest page(int offset, int limit) {
        return PageRequest.of(offset / limit, limit, Sort.by("id"));
    }

    private static Plant toDomain(PlantJpaEntity entity) {
        return Plant.restore(entity.getId(), entity.getOwnerId(), entity.getAssetId(),
            new ImageFingerprint(entity.getFingerprint(), entity.getFingerprintVersion()),
            entity.getTitle(), ModerationStatus.valueOf(entity.getModerationStatus()),
            entity.getModerationReason(), LifeStatus.valueOf(entity.getLifeStatus()),
            entity.getCreatedAt(), entity.getDiedAt(), entity.getArchivedAt(),
            entity.getVersion());
    }
}
```

`src/main/java/com/plantarena/plants/adapter/out/persistence/JpaImageRestrictionRepository.java`:

```java
package com.plantarena.plants.adapter.out.persistence;

import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.ImageRestrictionRepository;
import com.plantarena.plants.domain.RestrictionKind;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация порта ImageRestrictionRepository на JPA + PostgreSQL (раздел 14.2).
 * Append-only: save только создаёт записи, история не удаляется (раздел 6).
 */
@Repository
@Transactional
public class JpaImageRestrictionRepository implements ImageRestrictionRepository {

    private final ImageRestrictionJpaRepository restrictions;

    public JpaImageRestrictionRepository(ImageRestrictionJpaRepository restrictions) {
        this.restrictions = restrictions;
    }

    @Override
    public ImageRestriction save(ImageRestriction restriction) {
        return toDomain(restrictions.saveAndFlush(new ImageRestrictionJpaEntity(
            restriction.id(), restriction.ownerId(), restriction.fingerprint().value(),
            restriction.fingerprint().algorithmVersion(), restriction.kind().name(),
            restriction.expiresAt(), restriction.reason(), restriction.sourceEntryId(),
            restriction.createdAt())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImageRestriction> findByOwnerAndFingerprint(UUID ownerId, String fingerprintValue) {
        return restrictions.findByOwnerIdAndFingerprint(ownerId, fingerprintValue).stream()
            .map(JpaImageRestrictionRepository::toDomain).toList();
    }

    private static ImageRestriction toDomain(ImageRestrictionJpaEntity entity) {
        return ImageRestriction.restore(entity.getId(), entity.getOwnerId(),
            new ImageFingerprint(entity.getFingerprint(), entity.getFingerprintVersion()),
            RestrictionKind.valueOf(entity.getKind()), entity.getExpiresAt(),
            entity.getReason(), entity.getSourceEntryId(), entity.getCreatedAt());
    }
}
```

`src/main/java/com/plantarena/plants/adapter/out/persistence/JpaPlantReservationRepository.java`:

```java
package com.plantarena.plants.adapter.out.persistence;

import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.PlantReservation;
import com.plantarena.plants.domain.PlantReservationRepository;
import com.plantarena.plants.domain.ReservationStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация порта PlantReservationRepository на JPA + PostgreSQL (раздел 14.2).
 * find-or-create + update + saveAndFlush; set-инвариант «один активный резерв
 * на пару» — частичный уникальный индекс plant_reservation_active_pair_uidx.
 */
@Repository
@Transactional
public class JpaPlantReservationRepository implements PlantReservationRepository {

    private final PlantReservationJpaRepository reservations;

    public JpaPlantReservationRepository(PlantReservationJpaRepository reservations) {
        this.reservations = reservations;
    }

    @Override
    public PlantReservation save(PlantReservation reservation) {
        PlantReservationJpaEntity entity = reservations.findById(reservation.id())
            .orElseGet(() -> new PlantReservationJpaEntity(reservation.id(),
                reservation.ownerId(), reservation.plantId(), reservation.fingerprint().value(),
                reservation.fingerprint().algorithmVersion(), reservation.idempotencyKey(),
                reservation.createdAt()));
        entity.update(reservation.status().name(), reservation.releasedAt());
        return toDomain(reservations.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlantReservation> findById(UUID id) {
        return reservations.findById(id).map(JpaPlantReservationRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlantReservation> findByIdempotencyKey(UUID idempotencyKey) {
        return reservations.findByIdempotencyKey(idempotencyKey)
            .map(JpaPlantReservationRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlantReservation> findActiveByOwnerAndFingerprint(UUID ownerId,
                                                                      String fingerprintValue) {
        return reservations.findFirstByOwnerIdAndFingerprintAndStatus(ownerId,
                fingerprintValue, ReservationStatus.ACTIVE.name())
            .map(JpaPlantReservationRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PlantReservation> findActiveByPlantId(UUID plantId) {
        return reservations.findFirstByPlantIdAndStatus(plantId, ReservationStatus.ACTIVE.name())
            .map(JpaPlantReservationRepository::toDomain);
    }

    private static PlantReservation toDomain(PlantReservationJpaEntity entity) {
        return PlantReservation.restore(entity.getId(), entity.getOwnerId(), entity.getPlantId(),
            new ImageFingerprint(entity.getFingerprint(), entity.getFingerprintVersion()),
            entity.getIdempotencyKey(), ReservationStatus.valueOf(entity.getStatus()),
            entity.getCreatedAt(), entity.getReleasedAt());
    }
}
```

- [ ] **Step 5: Контрактные базы (с `@Transactional` на базовом классе!)**

`src/test/java/com/plantarena/plants/PlantRepositoryContractTest.java`:

```java
package com.plantarena.plants;

import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт PlantRepository (раздел 14.2): одинаковые гарантии у in-memory
 * фейка (application-тесты) и JPA + PostgreSQL (adapter-тесты) — честность фейка.
 *
 * <p>@Transactional обязателен на самом базовом классе: аннотация из @DataJpaTest
 * на подклассе не применяется к наследуемым тест-методам, и без неё JPA-контрактные
 * тесты автокоммитят и протекают в общую БД между контекстами (урок итерации 2,
 * фикс 56e8e3a — см. UserRepositoryContractTest/MediaAssetRepositoryContractTest).
 */
@DisplayName("Контракт PlantRepository")
@Transactional
public abstract class PlantRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    protected abstract PlantRepository repository();

    protected ImageFingerprint fingerprint(char digit) {
        return new ImageFingerprint("0".repeat(63) + digit, 1);
    }

    protected Plant submittedPlant(UUID ownerId, char fingerprintDigit) {
        return Plant.submit(ownerId, UUID.randomUUID(), fingerprint(fingerprintDigit),
            "Фикус", NOW);
    }

    @Test
    @DisplayName("сохранение и чтение по id: все поля")
    void сохранение_и_чтение_по_id() {
        Plant plant = submittedPlant(UUID.randomUUID(), '1');

        repository().save(plant);
        Plant loaded = repository().findById(plant.id()).orElseThrow();

        assertThat(loaded.id()).isEqualTo(plant.id());
        assertThat(loaded.ownerId()).isEqualTo(plant.ownerId());
        assertThat(loaded.assetId()).isEqualTo(plant.assetId());
        assertThat(loaded.fingerprint()).isEqualTo(plant.fingerprint());
        assertThat(loaded.title()).isEqualTo("Фикус");
        assertThat(loaded.moderationStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(loaded.moderationReason()).isNull();
        assertThat(loaded.lifeStatus().name()).isEqualTo("ALIVE");
        assertThat(loaded.createdAt()).isEqualTo(NOW);
        assertThat(loaded.diedAt()).isNull();
        assertThat(loaded.archivedAt()).isNull();
    }

    @Test
    @DisplayName("мутации модерации, жизни и архивации переживают сохранение")
    void мутации_переживают_сохранение() {
        Plant plant = submittedPlant(UUID.randomUUID(), '2');
        repository().save(plant);

        plant.applyDecision(ModerationStatus.REJECTED, "не растение");
        plant.die(NOW.plusSeconds(60));
        plant.archive(NOW.plusSeconds(120));
        repository().save(plant);

        Plant loaded = repository().findById(plant.id()).orElseThrow();
        assertThat(loaded.moderationStatus()).isEqualTo(ModerationStatus.REJECTED);
        assertThat(loaded.moderationReason()).isEqualTo("не растение");
        assertThat(loaded.lifeStatus().name()).isEqualTo("DEAD");
        assertThat(loaded.diedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(loaded.archivedAt()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    @DisplayName("несуществующий id — пустой результат")
    void несуществующий_id_пустой_результат() {
        assertThat(repository().findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("активное растение на файл; архивация освобождает файл (ADR-008)")
    void активное_растение_на_файл_архивация_освобождает() {
        UUID assetId = UUID.randomUUID();
        Plant plant = Plant.submit(UUID.randomUUID(), assetId, fingerprint('3'), "Фикус", NOW);
        repository().save(plant);

        assertThat(repository().findActiveByAssetId(assetId)).isPresent();

        plant.archive(NOW);
        repository().save(plant);

        assertThat(repository().findActiveByAssetId(assetId)).isEmpty();
    }

    @Test
    @DisplayName("список владельца: пагинация, сортировка по id, архив скрыт")
    void список_владельца_пагинация_и_архив() {
        UUID ownerId = UUID.randomUUID();
        for (int i = 1; i <= 3; i++) {
            repository().save(Plant.submit(ownerId, UUID.randomUUID(),
                fingerprint(Character.forDigit(i, 10)), "Растение " + i, NOW));
        }

        List<Plant> firstPage = repository().findByOwner(ownerId, 0, 2);
        assertThat(firstPage).hasSize(2);
        assertThat(firstPage.get(0).id().compareTo(firstPage.get(1).id())).isLessThan(0);
        assertThat(repository().findByOwner(ownerId, 2, 2)).hasSize(1);
        assertThat(repository().countByOwner(ownerId)).isEqualTo(3);

        Plant archived = firstPage.get(0);
        archived.archive(NOW);
        repository().save(archived);

        assertThat(repository().countByOwner(ownerId)).isEqualTo(2);
    }

    @Test
    @DisplayName("публичный список: только APPROVED без архивированных (ADR-008)")
    void публичный_список_только_approved() {
        UUID ownerId = UUID.randomUUID();
        Plant pending = submittedPlant(ownerId, '4');
        Plant approved = submittedPlant(ownerId, '5');
        approved.applyDecision(ModerationStatus.APPROVED, null);
        Plant archivedApproved = submittedPlant(ownerId, '6');
        archivedApproved.applyDecision(ModerationStatus.APPROVED, null);
        archivedApproved.archive(NOW);
        repository().save(pending);
        repository().save(approved);
        repository().save(archivedApproved);

        List<Plant> publicList = repository().findApprovedByOwner(ownerId, 0, 20);

        assertThat(publicList).hasSize(1);
        assertThat(publicList.get(0).id()).isEqualTo(approved.id());
        assertThat(repository().countApprovedByOwner(ownerId)).isEqualTo(1);
    }
}
```

`src/test/java/com/plantarena/plants/ImageRestrictionRepositoryContractTest.java`:

```java
package com.plantarena.plants;

import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.ImageRestrictionRepository;
import com.plantarena.plants.domain.RestrictionKind;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт ImageRestrictionRepository (раздел 14.2): честность фейка.
 * @Transactional обязателен на базовом классе (урок итерации 2, фикс 56e8e3a).
 */
@DisplayName("Контракт ImageRestrictionRepository")
@Transactional
public abstract class ImageRestrictionRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    protected static final ImageFingerprint FINGERPRINT = new ImageFingerprint("b".repeat(64), 1);

    protected abstract ImageRestrictionRepository repository();

    @Test
    @DisplayName("история запретов пары сохраняется полностью (append-only)")
    void история_запретов_пары_сохраняется_полностью() {
        UUID ownerId = UUID.randomUUID();
        UUID sourceEntryId = UUID.randomUUID();
        repository().save(ImageRestriction.permanent(ownerId, FINGERPRINT,
            "поражение в закрытом турнире", sourceEntryId, NOW));
        repository().save(ImageRestriction.cooldown(ownerId, FINGERPRINT,
            "поражение в глобальном турнире", null, NOW.plus(Duration.ofHours(24)),
            NOW.plusSeconds(30)));

        var history = repository().findByOwnerAndFingerprint(ownerId, FINGERPRINT.value());

        assertThat(history).hasSize(2); // история не удаляется и не перезаписывается
        assertThat(history).extracting(ImageRestriction::kind)
            .containsExactlyInAnyOrder(RestrictionKind.PERMANENT, RestrictionKind.COOLDOWN);
        assertThat(history).allSatisfy(restriction -> {
            assertThat(restriction.ownerId()).isEqualTo(ownerId);
            assertThat(restriction.fingerprint()).isEqualTo(FINGERPRINT);
            assertThat(restriction.reason()).isNotBlank();
        });
    }

    @Test
    @DisplayName("чужая пара — пустая история (допущение 3: область запрета)")
    void чужая_пара_пустая_история() {
        repository().save(ImageRestriction.permanent(UUID.randomUUID(), FINGERPRINT,
            "чужое поражение", null, NOW));

        assertThat(repository().findByOwnerAndFingerprint(UUID.randomUUID(), FINGERPRINT.value()))
            .isEmpty();
    }
}
```

`src/test/java/com/plantarena/plants/PlantReservationRepositoryContractTest.java`:

```java
package com.plantarena.plants;

import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.PlantReservation;
import com.plantarena.plants.domain.PlantReservationRepository;
import com.plantarena.plants.domain.ReservationStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Контракт PlantReservationRepository (раздел 14.2): честность фейка —
 * set-инвариант «один активный резерв на пару» обеспечивает и фейк, и БД.
 * @Transactional обязателен на базовом классе (урок итерации 2, фикс 56e8e3a).
 */
@DisplayName("Контракт PlantReservationRepository")
@Transactional
public abstract class PlantReservationRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    protected static final ImageFingerprint FINGERPRINT = new ImageFingerprint("c".repeat(64), 1);

    protected abstract PlantReservationRepository repository();

    private PlantReservation activeReservation(UUID ownerId, UUID plantId, UUID key) {
        return PlantReservation.reserve(ownerId, plantId, FINGERPRINT, key, NOW);
    }

    @Test
    @DisplayName("резерв сохраняется и находится по id и ключу идемпотентности")
    void резерв_сохраняется_и_находится_по_id_и_ключу() {
        UUID ownerId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        PlantReservation reservation = activeReservation(ownerId, UUID.randomUUID(), key);
        repository().save(reservation);

        assertThat(repository().findById(reservation.id())).isPresent();
        assertThat(repository().findByIdempotencyKey(key)).isPresent();
        assertThat(repository().findByIdempotencyKey(UUID.randomUUID())).isEmpty();
        assertThat(repository().findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("активный резерв находится по паре и по растению")
    void активный_резерв_находится_по_паре_и_растению() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        repository().save(activeReservation(ownerId, plantId, UUID.randomUUID()));

        assertThat(repository().findActiveByOwnerAndFingerprint(ownerId, FINGERPRINT.value()))
            .isPresent();
        assertThat(repository().findActiveByPlantId(plantId)).isPresent();
        assertThat(repository().findActiveByPlantId(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("второй активный резерв пары отклоняется (допущение 4)")
    void второй_активный_резерв_пары_отклоняется() {
        UUID ownerId = UUID.randomUUID();
        repository().save(activeReservation(ownerId, UUID.randomUUID(), UUID.randomUUID()));

        assertThatThrownBy(() -> repository()
            .save(activeReservation(ownerId, UUID.randomUUID(), UUID.randomUUID())))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("освобождение убирает из активных и позволяет новый резерв пары")
    void освобождение_убирает_из_активных_и_позволяет_новый() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        PlantReservation reservation = activeReservation(ownerId, plantId, UUID.randomUUID());
        repository().save(reservation);

        reservation.release(NOW.plusSeconds(60));
        repository().save(reservation);

        assertThat(repository().findActiveByOwnerAndFingerprint(ownerId, FINGERPRINT.value()))
            .isEmpty();
        assertThat(repository().findActiveByPlantId(plantId)).isEmpty();
        PlantReservation released = repository().findById(reservation.id()).orElseThrow();
        assertThat(released.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(released.releasedAt()).isEqualTo(NOW.plusSeconds(60));

        repository().save(activeReservation(ownerId, UUID.randomUUID(), UUID.randomUUID()));
    }
}
```

- [ ] **Step 6: In-memory наследники (surefire)**

`src/test/java/com/plantarena/plants/application/support/InMemoryPlantRepositoryContractTest.java`:

```java
package com.plantarena.plants.application.support;

import com.plantarena.plants.PlantRepositoryContractTest;
import com.plantarena.plants.domain.PlantRepository;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Контракт PlantRepository: in-memory фейк")
class InMemoryPlantRepositoryContractTest extends PlantRepositoryContractTest {

    private final InMemoryPlantRepository repository = new InMemoryPlantRepository();

    @Override
    protected PlantRepository repository() {
        return repository;
    }
}
```

`src/test/java/com/plantarena/plants/application/support/InMemoryImageRestrictionRepositoryContractTest.java`:

```java
package com.plantarena.plants.application.support;

import com.plantarena.plants.ImageRestrictionRepositoryContractTest;
import com.plantarena.plants.domain.ImageRestrictionRepository;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Контракт ImageRestrictionRepository: in-memory фейк")
class InMemoryImageRestrictionRepositoryContractTest extends ImageRestrictionRepositoryContractTest {

    private final InMemoryImageRestrictionRepository repository =
        new InMemoryImageRestrictionRepository();

    @Override
    protected ImageRestrictionRepository repository() {
        return repository;
    }
}
```

`src/test/java/com/plantarena/plants/application/support/InMemoryPlantReservationRepositoryContractTest.java`:

```java
package com.plantarena.plants.application.support;

import com.plantarena.plants.PlantReservationRepositoryContractTest;
import com.plantarena.plants.domain.PlantReservationRepository;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Контракт PlantReservationRepository: in-memory фейк")
class InMemoryPlantReservationRepositoryContractTest extends PlantReservationRepositoryContractTest {

    private final InMemoryPlantReservationRepository repository =
        new InMemoryPlantReservationRepository();

    @Override
    protected PlantReservationRepository repository() {
        return repository;
    }
}
```

- [ ] **Step 7: JPA-контрактные IT (failsafe, Testcontainers)**

`src/test/java/com/plantarena/plants/adapter/out/persistence/JpaPlantRepositoryContractIT.java`:

```java
package com.plantarena.plants.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.plants.PlantRepositoryContractTest;
import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import com.plantarena.support.PostgresSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Контракт PlantRepository на JPA + PostgreSQL (Testcontainers, раздел 14.2).
 * Миграции выполняет SchemaMigrationConfig (ADR-003), H2 не используется.
 * Контейнер singleton на JVM: перед каждым тестом таблицы plants чистятся
 * (внутри откатываемой транзакции @DataJpaTest).
 */
@DisplayName("Контракт PlantRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaPlantRepository.class})
class JpaPlantRepositoryContractIT extends PlantRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaPlantRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_растения_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from plants.plant_reservation");
        jdbcTemplate.update("delete from plants.image_restriction");
        jdbcTemplate.update("delete from plants.plant");
    }

    @Override
    protected PlantRepository repository() {
        return repository;
    }

    @Test
    @DisplayName("второе неархивированное растение на файл отклоняется индексом (ADR-008)")
    void второе_активное_растение_на_файл_отклоняется_индексом() {
        UUID ownerId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        ImageFingerprint fingerprint = new ImageFingerprint("e".repeat(64), 1);
        repository().save(Plant.submit(ownerId, assetId, fingerprint, "Первое", NOW));

        assertThatThrownBy(() -> repository()
            .save(Plant.submit(ownerId, assetId, fingerprint, "Второе", NOW)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("конкурентное обновление отклоняется оптимистической блокировкой (@Version)")
    void конкурентное_обновление_отклоняется_оптимистической_блокировкой() {
        Plant plant = submittedPlant(UUID.randomUUID(), '7');
        repository().save(plant);
        Plant loaded = repository().findById(plant.id()).orElseThrow();

        jdbcTemplate.update("update plants.plant set version = version + 1 where id = ?",
            plant.id()); // конкурирующая транзакция успела первой

        loaded.rename("Конкуренция");
        assertThatThrownBy(() -> repository().save(loaded))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
```

`src/test/java/com/plantarena/plants/adapter/out/persistence/JpaImageRestrictionRepositoryContractIT.java`:

```java
package com.plantarena.plants.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.plants.ImageRestrictionRepositoryContractTest;
import com.plantarena.plants.domain.ImageRestrictionRepository;
import com.plantarena.support.PostgresSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Контракт ImageRestrictionRepository на JPA + PostgreSQL (Testcontainers).
 * Контейнер singleton на JVM: перед каждым тестом таблицы plants чистятся.
 */
@DisplayName("Контракт ImageRestrictionRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaImageRestrictionRepository.class})
class JpaImageRestrictionRepositoryContractIT extends ImageRestrictionRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaImageRestrictionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_запреты_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from plants.plant_reservation");
        jdbcTemplate.update("delete from plants.image_restriction");
        jdbcTemplate.update("delete from plants.plant");
    }

    @Override
    protected ImageRestrictionRepository repository() {
        return repository;
    }

    @Test
    @DisplayName("COOLDOWN без срока отклоняется ограничением БД (CHECK)")
    void cooldown_без_срока_отклоняется_ограничением_бд() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
            insert into plants.image_restriction (id, owner_id, fingerprint,
                fingerprint_version, kind, expires_at, reason, source_entry_id, created_at)
            values (?, ?, ?, 1, 'COOLDOWN', null, 'без срока', null, now())
            """, UUID.randomUUID(), UUID.randomUUID(), "f".repeat(64)))
            .isInstanceOf(DataAccessException.class);
    }
}
```

`src/test/java/com/plantarena/plants/adapter/out/persistence/JpaPlantReservationRepositoryContractIT.java`:

```java
package com.plantarena.plants.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.plants.PlantReservationRepositoryContractTest;
import com.plantarena.plants.domain.PlantReservation;
import com.plantarena.plants.domain.PlantReservationRepository;
import com.plantarena.support.PostgresSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Контракт PlantReservationRepository на JPA + PostgreSQL (Testcontainers).
 * Контейнер singleton на JVM: перед каждым тестом таблицы plants чистятся.
 */
@DisplayName("Контракт PlantReservationRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaPlantReservationRepository.class})
class JpaPlantReservationRepositoryContractIT extends PlantReservationRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaPlantReservationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_резервы_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from plants.plant_reservation");
        jdbcTemplate.update("delete from plants.image_restriction");
        jdbcTemplate.update("delete from plants.plant");
    }

    @Override
    protected PlantReservationRepository repository() {
        return repository;
    }

    @Test
    @DisplayName("повтор того же ключа идемпотентности отклоняется уникальностью (UNIQUE)")
    void повтор_того_же_ключа_отклоняется_уникальностью() {
        UUID ownerId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        repository().save(PlantReservation.reserve(ownerId, UUID.randomUUID(),
            FINGERPRINT, key, NOW));

        assertThatThrownBy(() -> repository().save(
            PlantReservation.reserve(ownerId, UUID.randomUUID(), FINGERPRINT, key, NOW)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 8: Прогнать тесты**

```bash
./mvnw test -Dtest='com.plantarena.plants.*' > /tmp/it3-plants-unit.log 2>&1; echo "exit: $?"
grep -E "Tests run" /tmp/it3-plants-unit.log | tail -1
```

Ожидание: BUILD SUCCESS — домен, application и три In-memory контрактных наследника зелёные (фейки Task 3 честны к контрактам).

```bash
./mvnw verify -Dtest='com.plantarena.plants.*' -Dit.test='JpaPlantRepositoryContractIT,JpaImageRestrictionRepositoryContractIT,JpaPlantReservationRepositoryContractIT' > /tmp/it3-plants-it.log 2>&1; echo "exit: $?"
grep -E "Tests run" /tmp/it3-plants-it.log | tail -1
```

Ожидание: BUILD SUCCESS — три JPA-контрактных IT зелёные (Testcontainers), включая частичные уникальные индексы, CHECK-инварианты и optimistic locking. ArchUnit зелёный (JPA-сущности и Spring Data — только в `plants.adapter.out.persistence`). `PlantsApiIT` остаётся красным до Task 6 (нет `InProcessMediaGateway` и `IntegrationEventPublisher`) — ожидаемо.

- [ ] **Step 9: Коммит**

```bash
git add src/main/java/com/plantarena/plants src/main/resources/db/migration/plants src/test/java/com/plantarena/plants
git commit -m "feat(plants): persistence — V2 (plant, image_restriction, plant_reservation), JPA-адаптеры, контрактные тесты"
```

---

### Task 6: REST /plants, ACL-адаптер media, IntegrationEventPublisher — приёмочный IT зелёный

**Files:**
- Create: `src/main/java/com/plantarena/config/EventWiringConfig.java`
- Create: `src/main/java/com/plantarena/plants/adapter/out/media/InProcessMediaGateway.java`
- Create: `src/main/java/com/plantarena/plants/adapter/in/web/PlantController.java`
- Create: `src/main/java/com/plantarena/plants/adapter/in/web/SubmitPlantRequest.java`
- Create: `src/main/java/com/plantarena/plants/adapter/in/web/RenamePlantRequest.java`
- Create: `src/main/java/com/plantarena/plants/adapter/in/web/PlantResponse.java`
- Create: `src/main/java/com/plantarena/plants/adapter/in/web/PlantModerationResponse.java`
- Create: `src/main/java/com/plantarena/plants/adapter/in/web/PlantExceptionHandler.java`
- Edit: `src/main/java/com/plantarena/shared/web/ApiError.java` (+`retryAt`)
- Test: `src/test/java/com/plantarena/plants/adapter/in/web/PlantExceptionHandlerTest.java`

**Interfaces:**
- Consumes: use cases Task 3, `plants.api` (`PlantData`, `PlantModerationStatus`, `PlantLifeStatus`, `PlantNotFoundException`, `ModerationAlreadyDecidedException`), `media.api` Task 4 (`MediaAssets`, `MediaAssetClaims`, `AssetInUseException`), `CurrentActorProvider` (ADR-005), `PaginationParams`/`ApiError`/`TraceIdFilter` (shared.web), паттерны `UserController`/`MediaController`/`MediaExceptionHandler`(+Test).
- Produces: эндпоинты раздела 13 — `POST /api/v1/plants` (201+Location), `GET /api/v1/plants[?ownerId=&page=&size=]` (X-Total-Count), `GET /api/v1/plants/{id}`, `PATCH /api/v1/plants/{id}` (только title), `DELETE /api/v1/plants/{id}` (архивация, 204), `GET /api/v1/plants/{id}/moderation`; ACL-адаптер `InProcessMediaGateway` (in-process, лаба №2 — Feign); bean `IntegrationEventPublisher` (in-process, лаба №4 — Kafka); `ApiError.retryAt` (409 IMAGE_RESTRICTED со сроком повтора). `PlantsApiIT` (25 тестов) становится зелёным.

`PlantNotEligibleException`/`ReservationConflictException` сознательно НЕ переводятся в HTTP: это контракт `plants.api` для tournaments (итерация 5), REST-эндпоинтов для них нет.

- [ ] **Step 1: ApiError + retryAt (полный обновлённый файл)**

`src/main/java/com/plantarena/shared/web/ApiError.java`:

```java
package com.plantarena.shared.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Тело ошибки ProblemDetail-вида:
 * {type,title,status,detail,instance,code,fieldErrors,traceId,retryAt}
 * (раздел 13 требований; retryAt — срок повтора для 409 IMAGE_RESTRICTED,
 * null у ошибок без срока).
 */
public record ApiError(
        URI type,
        String title,
        int status,
        String detail,
        URI instance,
        String code,
        List<FieldError> fieldErrors,
        String traceId,
        Instant retryAt) {

    public record FieldError(String field, String message) {
    }

    /** Без retryAt — большинство ошибок не имеют срока повтора (совместимость). */
    public ApiError(URI type, String title, int status, String detail, URI instance,
                    String code, List<FieldError> fieldErrors, String traceId) {
        this(type, title, status, detail, instance, code, fieldErrors, traceId, null);
    }

    public ApiError withFieldErrors(List<FieldError> errors) {
        return new ApiError(type, title, status, detail, instance, code, errors, traceId, retryAt);
    }

    /** Срок, когда запрет истечёт (COOLDOWN); null — запрета по сроку нет. */
    public ApiError withRetryAt(Instant newRetryAt) {
        return new ApiError(type, title, status, detail, instance, code, fieldErrors, traceId,
            newRetryAt);
    }
}
```

Существующие advice-классы (`ApiExceptionHandler`, `IdentityExceptionHandler`, `MediaExceptionHandler`) не меняются: 8-аргументный конструктор сохранён, `withFieldErrors` переносит `retryAt`.

- [ ] **Step 2: EventWiringConfig — bean IntegrationEventPublisher**

`src/main/java/com/plantarena/config/EventWiringConfig.java`:

```java
package com.plantarena.config;

import com.plantarena.shared.event.IntegrationEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Публикация integration-событий в монолите — Spring ApplicationEventPublisher
 * (in-process). В лабах №2/4 реализация меняется здесь на Kafka-издателя:
 * домен и application контекстов не меняются (правило 10.2, ADR-006).
 */
@Configuration
public class EventWiringConfig {

    @Bean
    public IntegrationEventPublisher integrationEventPublisher(ApplicationEventPublisher publisher) {
        return publisher::publishEvent;
    }
}
```

- [ ] **Step 3: ACL-адаптер media (in-process)**

`src/main/java/com/plantarena/plants/adapter/out/media/InProcessMediaGateway.java`:

```java
package com.plantarena.plants.adapter.out.media;

import com.plantarena.media.api.AssetInUseException;
import com.plantarena.media.api.MediaAssetClaims;
import com.plantarena.media.api.MediaAssets;
import com.plantarena.plants.application.AssetAlreadyClaimedException;
import com.plantarena.plants.application.port.out.MediaAssetClaimsGateway;
import com.plantarena.plants.application.port.out.MediaAssetsGateway;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL-адаптер media → plants (раздел 4.3, Customer–Supplier): in-process вызов
 * опубликованного контракта media.api. Потребитель владеет портом со своими
 * типами (MediaAssetsGateway/MediaAssetClaimsGateway); в лабе №2 адаптер
 * меняется на HTTP/Feign, домен и application plants не меняются.
 * Конфликт задействованности переводится в термины plants.
 */
@Component
public class InProcessMediaGateway implements MediaAssetsGateway, MediaAssetClaimsGateway {

    private final MediaAssets mediaAssets;
    private final MediaAssetClaims mediaAssetClaims;

    public InProcessMediaGateway(MediaAssets mediaAssets, MediaAssetClaims mediaAssetClaims) {
        this.mediaAssets = mediaAssets;
        this.mediaAssetClaims = mediaAssetClaims;
    }

    @Override
    public Optional<AssetMetadata> findById(UUID assetId) {
        return mediaAssets.findById(assetId)
            .map(asset -> new AssetMetadata(asset.id(), asset.ownerId(),
                asset.fingerprint(), asset.fingerprintVersion()));
    }

    @Override
    public void claim(UUID assetId, UUID plantId, boolean publiclyVisible) {
        try {
            mediaAssetClaims.claim(assetId, plantId, publiclyVisible);
        } catch (AssetInUseException e) {
            throw new AssetAlreadyClaimedException(e.getMessage());
        }
    }

    @Override
    public void release(UUID assetId, UUID plantId) {
        mediaAssetClaims.release(assetId, plantId);
    }
}
```

- [ ] **Step 4: DTO веб-слоя**

`src/main/java/com/plantarena/plants/adapter/in/web/SubmitPlantRequest.java`:

```java
package com.plantarena.plants.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Тело POST /plants (раздел 13). */
public record SubmitPlantRequest(
        @NotNull UUID assetId,
        @NotBlank @Size(min = 1, max = 100) String title) {
}
```

`src/main/java/com/plantarena/plants/adapter/in/web/RenamePlantRequest.java`:

```java
package com.plantarena.plants.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Тело PATCH /plants/{id}: только title (asset/owner менять нельзя, раздел 13). */
public record RenamePlantRequest(@NotBlank @Size(min = 1, max = 100) String title) {
}
```

`src/main/java/com/plantarena/plants/adapter/in/web/PlantResponse.java`:

```java
package com.plantarena.plants.adapter.in.web;

import com.plantarena.plants.api.PlantData;
import com.plantarena.plants.api.PlantLifeStatus;
import com.plantarena.plants.api.PlantModerationStatus;
import java.time.Instant;
import java.util.UUID;

/** Ответ растений: без fingerprint — отпечаток внутренний инструмент запретов. */
public record PlantResponse(UUID id, UUID ownerId, UUID assetId, String title,
                            PlantModerationStatus moderationStatus, PlantLifeStatus lifeStatus,
                            Instant createdAt, Instant diedAt, Instant archivedAt) {

    public static PlantResponse from(PlantData plant) {
        return new PlantResponse(plant.id(), plant.ownerId(), plant.assetId(), plant.title(),
            plant.moderationStatus(), plant.lifeStatus(), plant.createdAt(), plant.diedAt(),
            plant.archivedAt());
    }
}
```

`src/main/java/com/plantarena/plants/adapter/in/web/PlantModerationResponse.java`:

```java
package com.plantarena.plants.adapter.in.web;

import com.plantarena.plants.api.PlantModerationStatus;
import com.plantarena.plants.application.port.in.GetPlantModerationUseCase;

/** Ответ GET /plants/{id}/moderation: статус, причина, разрешён ли повторный upload. */
public record PlantModerationResponse(PlantModerationStatus moderationStatus, String reason,
                                      boolean retryUploadAllowed) {

    public static PlantModerationResponse from(GetPlantModerationUseCase.ModerationStatusResult result) {
        return new PlantModerationResponse(result.moderationStatus(), result.reason(),
            result.retryUploadAllowed());
    }
}
```

- [ ] **Step 5: PlantController**

`src/main/java/com/plantarena/plants/adapter/in/web/PlantController.java`:

```java
package com.plantarena.plants.adapter.in.web;

import com.plantarena.plants.api.PlantData;
import com.plantarena.plants.application.port.in.ArchivePlantUseCase;
import com.plantarena.plants.application.port.in.GetPlantModerationUseCase;
import com.plantarena.plants.application.port.in.GetPlantUseCase;
import com.plantarena.plants.application.port.in.ListPlantsUseCase;
import com.plantarena.plants.application.port.in.RenamePlantUseCase;
import com.plantarena.plants.application.port.in.SubmitPlantUseCase;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.shared.web.PaginationParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ручки растений (раздел 13). Контроллер обращается только к входным портам
 * application (правило 10.2.7); видимость и права решает use case/AccessPolicy.
 */
@RestController
@RequestMapping("/api/v1/plants")
@Tag(name = "plants")
public class PlantController {

    private final SubmitPlantUseCase submitPlant;
    private final ListPlantsUseCase listPlants;
    private final GetPlantUseCase getPlant;
    private final RenamePlantUseCase renamePlant;
    private final ArchivePlantUseCase archivePlant;
    private final GetPlantModerationUseCase getPlantModeration;
    private final CurrentActorProvider currentActorProvider;

    public PlantController(SubmitPlantUseCase submitPlant, ListPlantsUseCase listPlants,
                           GetPlantUseCase getPlant, RenamePlantUseCase renamePlant,
                           ArchivePlantUseCase archivePlant,
                           GetPlantModerationUseCase getPlantModeration,
                           CurrentActorProvider currentActorProvider) {
        this.submitPlant = submitPlant;
        this.listPlants = listPlants;
        this.getPlant = getPlant;
        this.renamePlant = renamePlant;
        this.archivePlant = archivePlant;
        this.getPlantModeration = getPlantModeration;
        this.currentActorProvider = currentActorProvider;
    }

    @PostMapping
    @Operation(operationId = "plants-submit-plant",
        summary = "Подать заявку «это моё растение» на свой файл (USER и выше)")
    public ResponseEntity<PlantResponse> submit(@Valid @RequestBody SubmitPlantRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        PlantData plant = submitPlant.submit(actor,
            new SubmitPlantUseCase.SubmitPlantCommand(request.assetId(), request.title()));
        return ResponseEntity
            .created(URI.create("/api/v1/plants/" + plant.id()))
            .body(PlantResponse.from(plant));
    }

    @GetMapping
    @Operation(operationId = "plants-list-plants",
        summary = "Список растений (свои — все статусы, чужие — только одобренные), X-Total-Count")
    public ResponseEntity<List<PlantResponse>> list(
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        ListPlantsUseCase.PlantListResult result =
            listPlants.list(actor, ownerId, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(result.items().stream().map(PlantResponse::from).toList());
    }

    @GetMapping("/{id}")
    @Operation(operationId = "plants-get-plant",
        summary = "Просмотр растения (владелец/админ всегда; чужие — только одобренные)")
    public PlantResponse get(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return PlantResponse.from(getPlant.get(actor, id));
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "plants-rename-plant",
        summary = "Переименовать растение (только владелец; только title)")
    public PlantResponse rename(@PathVariable UUID id, @Valid @RequestBody RenamePlantRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        return PlantResponse.from(renamePlant.rename(actor, id, request.title()));
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "plants-archive-plant",
        summary = "Архивировать растение (только владелец; вне активного резерва)")
    public ResponseEntity<Void> archive(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        archivePlant.archive(actor, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/moderation")
    @Operation(operationId = "plants-get-moderation",
        summary = "Статус модерации заявки (только владелец)")
    public PlantModerationResponse moderation(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return PlantModerationResponse.from(getPlantModeration.moderation(actor, id));
    }
}
```

- [ ] **Step 6: PlantExceptionHandler**

`src/main/java/com/plantarena/plants/adapter/in/web/PlantExceptionHandler.java`:

```java
package com.plantarena.plants.adapter.in.web;

import com.plantarena.plants.api.ModerationAlreadyDecidedException;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.application.AssetAlreadyClaimedException;
import com.plantarena.plants.application.AssetNotFoundException;
import com.plantarena.plants.application.ImageRestrictedException;
import com.plantarena.plants.application.PlantUnderReservationException;
import com.plantarena.shared.web.ApiError;
import com.plantarena.shared.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Перевод исключений plants в ProblemDetail-подобное тело (раздел 13:
 * 404 не найдено/скрыто, 409 конфликты задействованности/запретов/резерва).
 * PlantNotEligibleException/ReservationConflictException — контракт api для
 * tournaments (итерация 5), HTTP-перевода не имеют. Живёт в adapter.in.web:
 * shared не зависит от контекстов (правило 10.2.8).
 */
@RestControllerAdvice
public class PlantExceptionHandler {

    @ExceptionHandler(PlantNotFoundException.class)
    public ResponseEntity<ApiError> plantNotFound(PlantNotFoundException e,
                                                  HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "PLANT_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(AssetNotFoundException.class)
    public ResponseEntity<ApiError> assetNotFound(AssetNotFoundException e,
                                                  HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(AssetAlreadyClaimedException.class)
    public ResponseEntity<ApiError> alreadyClaimed(AssetAlreadyClaimedException e,
                                                    HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "ASSET_ALREADY_CLAIMED", e.getMessage(), request);
    }

    /** 409 + retryAt: для COOLDOWN — срок истечения, для PERMANENT — null. */
    @ExceptionHandler(ImageRestrictedException.class)
    public ResponseEntity<ApiError> imageRestricted(ImageRestrictedException e,
                                                    HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
            URI.create("about:blank"), HttpStatus.CONFLICT.getReasonPhrase(),
            HttpStatus.CONFLICT.value(), e.getMessage(),
            URI.create(request.getRequestURI()), "IMAGE_RESTRICTED", List.of(), traceId,
            e.retryAt()));
    }

    @ExceptionHandler(PlantUnderReservationException.class)
    public ResponseEntity<ApiError> underReservation(PlantUnderReservationException e,
                                                      HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "PLANT_UNDER_RESERVATION", e.getMessage(), request);
    }

    @ExceptionHandler(ModerationAlreadyDecidedException.class)
    public ResponseEntity<ApiError> alreadyDecided(ModerationAlreadyDecidedException e,
                                                    HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "MODERATION_ALREADY_DECIDED", e.getMessage(), request);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String detail,
                                              HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(status).body(new ApiError(
            URI.create("about:blank"), status.getReasonPhrase(), status.value(), detail,
            URI.create(request.getRequestURI()), code, List.of(), traceId));
    }
}
```

- [ ] **Step 7: Тест обработчика ошибок**

`src/test/java/com/plantarena/plants/adapter/in/web/PlantExceptionHandlerTest.java`:

```java
package com.plantarena.plants.adapter.in.web;

import com.plantarena.plants.api.ModerationAlreadyDecidedException;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.application.AssetAlreadyClaimedException;
import com.plantarena.plants.application.AssetNotFoundException;
import com.plantarena.plants.application.ImageRestrictedException;
import com.plantarena.plants.application.PlantUnderReservationException;
import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.shared.web.ApiError;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Ошибки plants: 404 скрытые, 409 конфликты задействованности и запретов")
class PlantExceptionHandlerTest {

    private final PlantExceptionHandler handler = new PlantExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/plants");

    @Test
    void скрытое_растение_даёт_404_plant_not_found() {
        ResponseEntity<ApiError> response = handler.plantNotFound(
            new PlantNotFoundException("Растение не найдено"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("PLANT_NOT_FOUND");
    }

    @Test
    void чужой_файл_даёт_404_asset_not_found() {
        ResponseEntity<ApiError> response = handler.assetNotFound(
            new AssetNotFoundException("Файл не найден"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("ASSET_NOT_FOUND");
    }

    @Test
    void занятый_файл_даёт_409_asset_already_claimed() {
        ResponseEntity<ApiError> response = handler.alreadyClaimed(
            new AssetAlreadyClaimedException("Файл уже задействован"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("ASSET_ALREADY_CLAIMED");
    }

    @Test
    void постоянный_запрет_даёт_409_image_restricted_без_срока() {
        ImageRestrictedException exception = new ImageRestrictedException(
            ImageRestriction.permanent(UUID.randomUUID(),
                new ImageFingerprint("a".repeat(64), 1),
                "поражение в закрытом турнире", null,
                Instant.parse("2026-09-25T10:00:00Z")));

        ResponseEntity<ApiError> response = handler.imageRestricted(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("IMAGE_RESTRICTED");
        assertThat(response.getBody().retryAt()).isNull(); // PERMANENT — повтора не будет
    }

    @Test
    void суточный_запрет_даёт_409_со_сроком_повтора() {
        ImageRestrictedException exception = new ImageRestrictedException(
            ImageRestriction.cooldown(UUID.randomUUID(),
                new ImageFingerprint("b".repeat(64), 1),
                "поражение в глобальном турнире", null,
                Instant.parse("2026-09-26T10:00:00Z"),
                Instant.parse("2026-09-25T10:00:00Z")));

        ResponseEntity<ApiError> response = handler.imageRestricted(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("IMAGE_RESTRICTED");
        assertThat(response.getBody().retryAt())
            .isEqualTo(Instant.parse("2026-09-26T10:00:00Z")); // COOLDOWN — срок есть
    }

    @Test
    void резерв_и_решённая_модерация_дают_409() {
        ResponseEntity<ApiError> reserved = handler.underReservation(
            new PlantUnderReservationException("Растение под активным резервом"), request);
        ResponseEntity<ApiError> decided = handler.alreadyDecided(
            new ModerationAlreadyDecidedException("Решение уже зафиксировано"), request);

        assertThat(reserved.getStatusCode().value()).isEqualTo(409);
        assertThat(reserved.getBody().code()).isEqualTo("PLANT_UNDER_RESERVATION");
        assertThat(decided.getStatusCode().value()).isEqualTo(409);
        assertThat(decided.getBody().code()).isEqualTo("MODERATION_ALREADY_DECIDED");
    }
}
```

- [ ] **Step 8: Прогнать — приёмочный IT зелёный**

```bash
./mvnw verify -Dit.test='PlantsApiIT' > /tmp/it3-green.log 2>&1; echo "exit: $?"
grep -E "Tests run" /tmp/it3-green.log | tail -1
```

Ожидание: BUILD SUCCESS — все 25 тестов `PlantsApiIT` зелёные (surefire гоняет все юнит-тесты — тоже зелёные; остальные IT отфильтрованы `-Dit.test` и пойдут полным `verify` в Task 8). Если отдельные сценарии красные — чинить по одному, сверяясь с ожиданиями разделов 6/13; не ослаблять asserts.

- [ ] **Step 9: Коммит**

```bash
git add src/main/java/com/plantarena/config/EventWiringConfig.java src/main/java/com/plantarena/plants src/main/java/com/plantarena/shared/web/ApiError.java src/test/java/com/plantarena/plants
git commit -m "feat(plants): REST /api/v1/plants, ACL-адаптер media, IntegrationEventPublisher — приёмочный IT зелёный"
```

---

### Task 7: Документация — ADR-008, context map, глоссарий, агрегаты, тесты допущений, README

**Files:**
- Create: `docs/domain/adr/ADR-008-asset-claims-and-visibility.md`
- Edit: `docs/domain/context-map.md`
- Edit: `docs/domain/glossary.md`
- Edit: `docs/domain/aggregates.md`
- Edit: `docs/domain/adr/ADR-006-product-assumptions.md`
- Edit: `README.md`

**Interfaces:**
- Consumes: реализованные решения Tasks 1–6; стиль ADR-007 (Контекст/Решение/Последствия/Файлы) и раздела «Файлы (media)» README.
- Produces: ADR-008 «Задействованность и публичная видимость файлов»; обновлённые context map (команды claim/release), глоссарий (Архив растения, Задействованность файла), реестр агрегатов (тесты plants), имена защищающих тестов допущений 1–5 (plants-части), раздел README «Растения (plants)».

- [ ] **Step 1: ADR-008**

`docs/domain/adr/ADR-008-asset-claims-and-visibility.md`:

```markdown
# ADR-008: Задействованность и публичная видимость файлов

Статус: принято (итерация 3).

## Контекст

Разделы 6 и 13 требований: файл «задействуется» растением при подаче заявки;
публично виден чужим только через одобренное растение; удаление задействованного
файла запрещено (409); архивация растения освобождает файл. plants — downstream
от media (раздел 4.3): media не может зависеть от plants (plants уже зависит от
media ради метаданных asset — иначе цикл).

## Решение

1. Задействованность (`AssetClaim`: `assetId` PK, `plantId`, `publiclyVisible`,
   `claimedAt`) хранится в **media** (таблица `media.asset_claim`), потому что
   media — upstream. plants командует через опубликованный контракт
   `media.api.MediaAssetClaims` (Customer–Supplier): подача заявки —
   `claim(assetId, plantId, false)`, одобрение модерации — `claim(..., true)`
   (upsert публичности), архивация — `release`. Все команды идемпотентны
   (повтор доставки безопасен).
2. Публичная видимость файла = его растение APPROVED и не архивировано.
   media не знает про модерацию: plants сообщает результат командой `claim`
   с флагом. `GET /api/v1/files/{id}`: владелец — всегда; чужие — только
   публично задействованный файл (иначе 404, скрыт приватностью).
3. Один файл — одно неархивированное растение. Два частных уникальных
   ограничения: PK `media.asset_claim` по `asset_id` и
   `plants.plant_active_asset_uidx ON plant(asset_id) WHERE archived_at IS NULL`.
   Удаление задействованного файла — 409 `ASSET_IN_USE`.
4. Межагрегатные транзакции — отступления от «одна транзакция — один агрегат»
   (раздел 12): подача заявки — `Plant` (plants) + `AssetClaim` (media) в одной
   транзакции монолита; гибель — `Plant` + `ImageRestriction` в plants.
   В лабе №2 — saga-команды с retry и компенсацией (release claim при неудачной
   подаче), в лабе №4 — outbox → Kafka. Идемпотентность команд уже обеспечена
   (upsert claim, no-op release, DEAD → no-op).

## Последствия

- media хранит `plantId`, не зная контекста plants: это ключ команды, не ссылка
  на агрегат; FK не создаётся (контексты выделяются в сервисы, раздел 10.4).
- Публичности файла «самой по себе» не существует: только через одобренное
  растение.
- Архивация растения — единственный штатный способ освободить файл; история
  растения, запреты и резервы при архивации сохраняются.
- В лабе №2 `InProcessMediaGateway` заменяется на Feign-адаптер с тем же портом
  `MediaAssetClaimsGateway`: меняется только адаптер.

## Файлы

- `media.api`: `MediaAssets`, `MediaAssetClaims`, `AssetInUseException`
- `media.domain.AssetClaim`; миграция `db/migration/media/V3__asset_claims.sql`
- `media.application`: `MediaAssetsFacade`, `MediaAssetClaimsFacade`
- `plants.adapter.out.media.InProcessMediaGateway` (ACL)
- `db/migration/plants/V2__plants.sql` (`plant_active_asset_uidx`)
- Тесты: `MediaAssetClaimsFacadeTest`, `MediaAssetServiceTest`
  (задействованность), `PlantServiceTest`, `PlantsApiIT` (одобрение раскрывает
  файл чужим; удаление занятого файла — 409; архивация освобождает)
```

- [ ] **Step 2: context-map.md**

В `docs/domain/context-map.md` три правки.

Диаграмма — ребро plants→media (после `plants[plants<br/>Plant, запреты, резервы]`-блока):

```mermaid
    plants -->|ACL: метаданные asset, команды задействованности| media
```

(заменяет `plants -->|ACL: метаданные asset| media`).

Таблица «Допустимые зависимости», строка plants:

```markdown
| plants | media | Customer–Supplier; ACL над `MediaAsset`, команды задействованности `MediaAssetClaims` (ADR-008) |
```

Таблица взаимодействий — добавить строку после `| plants | media | метаданные ... |`:

```markdown
| plants | media | `MediaAssetClaims.claim/release` (задействованность, публичность) | команда | синхронно, в транзакции подачи/архивации | Feign + компенсация | Kafka-команды |
```

Раздел «Правила, устраняющие циклы» — добавить пункт:

```markdown
- plants сообщает media о задействованности и публичности файла командами
  `media.api.MediaAssetClaims.claim/release`; media хранит задействованность,
  но не знает о растениях (ADR-008) — plantId это ключ команды, не ссылка.
```

- [ ] **Step 3: glossary.md**

В `docs/domain/glossary.md` добавить строки после «Резерв изображения»:

```markdown
| Задействованность файла | `AssetClaim` / `MediaAssetClaims` | media (хранит), plants (командует) | Файл занят растением: один файл — одно неархивированное растение; публичность — после одобрения (ADR-008) |
| Архив растения | `Plant.archive(...)` | plants | Скрытие растения владельцем с освобождением файла; история, запреты и резервы сохраняются |
```

- [ ] **Step 4: aggregates.md**

В `docs/domain/aggregates.md` заполнить колонку «Защищающий тест» трёх строк plants:

```markdown
| plants | `Plant` | ... | `PlantTest` (переходы модерации/жизни, неизменяемость asset), `PlantServiceTest` (подача/видимость/архивация/запреты), `PlantsApiIT` (через HTTP) |
| plants | `ImageRestriction` | ... | `ImageRestrictionTest` (фабрики, инвариант kind–expiresAt), `ImageReusePolicyTest` (PERMANENT приоритетнее, истёкший COOLDOWN не блокирует), `PlantLifecycleServiceTest` (гибель создаёт запрет один раз) |
| plants | `PlantReservation` | ... | `PlantReservationTest` (переходы), `PlantReservationRepositoryContractTest` + `JpaPlantReservationRepositoryContractIT` (частичный уникальный индекс), `PlantEligibilityServiceTest` (идемпотентность по ключу) |
```

(остальные колонки строк не меняются). В списке «Доменные сервисы и политики» заменить строку:

```markdown
- `ImageReusePolicy` — реализован (итерация 3): `ImageReusePolicyTest`
```

- [ ] **Step 5: ADR-006 — имена защищающих тестов допущений 1–5**

В `docs/domain/adr/ADR-006-product-assumptions.md` заменить колонку «Защищающий тест» (plants-части; строки 6–7 допущений не трогать):

```markdown
| 1 | ... | `PlantLifecycleServiceTest.погибшее_растение_не_воскресает_повторная_гибель_создаёт_запрет_один_раз`, `PlantsApiIT.погибшее_растение_не_воскресает_повторная_загрузка_создаёт_новый_экземпляр` |
| 2 | ... | `PlantLifecycleServiceTest.постоянный_запрет_приоритетнее_временного_при_накоплении`, `PlantServiceTest.суточный_запрет_блокирует_совпавшую_картинку_до_истечения`, `PlantsApiIT.постоянный_запрет_блокирует_ту_же_картинку_навсегда`, `PlantsApiIT.суточный_запрет_блокирует_совпавшую_картинку_до_истечения` (турнирные части — итерации 6–7) |
| 3 | ... | `PlantServiceTest.чужое_поражение_не_блокирует_фотографию_у_всех`, `PlantsApiIT.чужое_поражение_не_блокирует_фотографию_у_всех` |
| 4 | ... | `PlantEligibilityServiceTest.одно_изображение_владельца_не_участвует_одновременно_в_нескольких_турнирах`, `PlantReservationRepositoryContractTest.второй_активный_резерв_пары_отклоняется`, `PlantsApiIT.резерв_изображения_идемпотентен_по_ключу_и_одиночен_на_пару` |
| 5 | ... | plants-часть: `PlantServiceTest.суточный_запрет_касается_только_совпавшей_картинки`, `PlantsApiIT.суточный_запрет_касается_только_совпавшей_картинки` (глобальное участие — итерация 7) |
```

- [ ] **Step 6: README — раздел «Растения (plants)» и актуализация media**

В `README.md` обновить два пункта раздела «Файлы (media)» (обещания итерации 3 выполнены):

```markdown
- Скачивание — `GET /api/v1/files/{id}`: владелец — всегда; чужим файл доступен
  только через одобренное растение (ADR-008, итерация 3).
- Удаление — `DELETE /api/v1/files/{id}`: владелец или админ; задействованный
  растением файл — 409 `ASSET_IN_USE` (итерация 3).
```

Добавить раздел после «Файлы (media)»:

```markdown
## Растения (plants)

Подача заявки — `POST /api/v1/plants` (`assetId` своего файла + `title`): создаёт
растение PENDING/ALIVE, задействует файл (один файл — одно неархивированное
растение, ADR-008) и публикует `PlantSubmitted` (модерация — итерация 4).
Повторное использование запрещённого изображения — 409 `IMAGE_RESTRICTED`:
навсегда после поражения в закрытом турнире, на 24 часа после глобального
(`retryAt` — срок истечения суточного).

- Список — `GET /api/v1/plants[?ownerId=]`: свои — все статусы, чужие — только
  APPROVED; пагинация `page`/`size`, заголовок `X-Total-Count`.
- Просмотр — `GET /api/v1/plants/{id}`: владелец/админ всегда; чужие — только
  одобренные (скрытое — 404).
- Переименование — `PATCH /api/v1/plants/{id}`: только владелец, только `title`.
- Архивация — `DELETE /api/v1/plants/{id}`: только владелец, вне активного
  резерва турнира (409 `PLANT_UNDER_RESERVATION`, резервы — итерация 5);
  скрывает растение и освобождает файл.
- Статус модерации — `GET /api/v1/plants/{id}/moderation`: только владелец.

Одобрение модерации делает файл растения публично видимым чужим
(`GET /api/v1/files/{id}`); архивация возвращает приватность (ADR-008).
```

- [ ] **Step 7: Коммит**

```bash
git add docs README.md
git commit -m "docs: ADR-008 (задействованность и публичность файлов), context map, глоссарий, агрегаты, тесты допущений 1–5, README"
```

---

### Task 8: Финальная проверка и merge

**Files:**
- Modify: ничего (только проверки и merge).

**Interfaces:**
- Consumes: всё итерации 3 (Tasks 1–7).
- Produces: ветка `feat/iteration-3-plants` слита в `main` (локально, без push); `./mvnw verify` зелёный на `main`.

- [ ] **Step 1: Полный verify на ветке**

```bash
./mvnw verify
```

Ожидание: BUILD SUCCESS — все unit/application/контрактные/приёмочные IT зелёные (включая `PlantsApiIT`, 25 тестов), ArchUnit зелёный, JaCoCo LINE ≥ 70% (gate «All coverage checks have been met»).

- [ ] **Step 2: Отчёт о покрытии**

```bash
python3 -c "
import xml.etree.ElementTree as t
r = t.parse('target/site/jacoco-merged/jacoco.xml').getroot()
for c in r.findall('counter'):
    if c.get('type') == 'LINE':
        m, co = int(c.get('missed')), int(c.get('covered'))
        print(f'LINE: {co}/{m+co} = {100*co/(m+co):.1f}%')
"
```

Ожидание: LINE ≥ 70% (по итогам итерации 2 было 96.4%; новые классы plants и media.api покрыты тестами всех уровней — домен, application, контракты репозиториев, HTTP).

- [ ] **Step 3: Merge в main (локально, без push)**

```bash
git checkout main
git merge --no-ff feat/iteration-3-plants -m "merge: итерация 3 — plants"
./mvnw verify
git log --oneline -3
```

Ожидание: merge без конфликтов; verify на `main` BUILD SUCCESS; push НЕ выполняется (только по отдельной команде пользователя).

- [ ] **Step 4: Чекпоинт-отчёт**

Краткий отчёт пользователю: что готово (агрегаты Plant/ImageRestriction/PlantReservation, контракт plants.api + события, REST /api/v1/plants, задействованность и публичная видимость файлов в media, миграции plants V2 + media V3, ADR-008 и docs), результаты verify и покрытия, отклонения от плана (если были), что отложено (автоматическая модерация — итерация 4: подписка на `PlantSubmitted`, `ModerationJob`, классификатор; использование резервов tournaments — итерация 5; «одно активное глобальное участие на пользователя» — итерация 7; guest-просмотр публичных файлов — итерация 8), ссылка на план следующей итерации (moderation). Дождаться review перед итерацией 4.

---

## Самопроверка плана (выполнена при написании)

- Сигнатуры `PlantService`/`PlantModerationService`/`PlantEligibilityService`/`PlantLifecycleService` (Task 3) соответствуют интерфейсам `plants.api` (Task 1) и используются контроллером (Task 6) без изменений.
- `MediaAssetService`/`MediaAccessPolicy` (Task 4) — единственное место правок media; конструктор `MediaAssetServiceTest` обновлён в том же таске; `MediaApiIT` не меняется (без задействованности поведение прежнее).
- Контрактные базы репозиториев (Task 5) несут `@Transactional` на абстрактном классе — урок итерации 2 (фикс 56e8e3a).
- `ApiError` (Task 6) получает `retryAt` с сохранением 8-аргументного конструктора — существующие advice-классы не меняются.
- Именованные тесты допущений 1–5 (Tasks 1, 3) совпадают с именами, внесёнными в ADR-006 (Task 7).
- ArchUnit: `media.api` не зависит от `media.domain`/`adapter`; `plants.adapter.out.media` зависит только от `media.api` (+ `plants.application` своего контекста — разрешено правилом «только через api чужого контекста»); `PlantExceptionHandler` → `plants.api` разрешено (запрещены только domain и adapter.out.persistence).



