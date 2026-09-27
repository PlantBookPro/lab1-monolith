# Итерация 5 (tournaments): закрытые турниры — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Контекст `tournaments` (private): агрегаты `Tournament` (state machine DRAFT → REGISTRATION_OPEN → RUNNING → FINISHED; DRAFT/REGISTRATION_OPEN → CANCELLED), `Invitation` (переходы INVITED → ACCEPTED_PENDING_MODERATION → READY / DECLINED / REVOKED / EXPIRED, дедлайн), `TournamentEntry` (ACTIVE при старте), `Tag` + M2M `tournament_tag`; use cases: создание/правка/удаление черновика, приглашения, принятие с резервом (одна tx, ADR-010), реакция на `PlantModerationDecided` (READY/возврат в INVITED), старт по дедлайну/вручную — один use case для ручки, scheduler'а и demo-ручки, отмена при нехватке участников (INSUFFICIENT_PARTICIPANTS); новые контракты `identity.api.UserDirectory` и `plants.api.PlantDirectory`; REST `/tournaments`, `/invitations`, `/me/invitations`, `/tags`, `/internal/demo/jobs/run-due`; события `TournamentStarted`/`InvitationCreated`; миграция V2 схемы `tournaments`; приёмочный `TournamentsApiIT`; docs (ADR-010, глоссарий, aggregates, context map, README).

**Architecture:** Модульный монолит, bounded context `tournaments` (api/domain/application/adapter) — downstream от identity и plants (раздел 4.3). Потребитель владеет портами: `tournaments.application.port.out.{PlantEligibilityGateway, PlantDirectoryGateway, ParticipantDirectoryGateway}`; ACL-адаптеры `tournaments.adapter.out.{plants,identity}` вызывают `plants.api`/`identity.api`. Подписка на `PlantModerationDecided` — in-process Spring-событие, слушатель в `tournaments.adapter.in.events`, синхронно в tx применения решения модерации (обязательное последствие, раздел 10.3). Scheduler `adapter.in.jobs` (fixedDelay 2с) и demo-ручка вызывают тот же `StartTournamentUseCase.startDue`. Первый `VotingWindow` — итерация 6 (спека итерации 5, решение 2).

**Tech Stack:** Java 21, Spring Boot 4.0.8 (MVC, Data JPA, @EnableScheduling — уже включён `ModerationWiringConfig`), PostgreSQL 17.5 + Flyway по контекстам (ADR-003), Testcontainers 2.0.5, ArchUnit 1.5.0, JaCoCo 0.8.15, Awaitility (test).

## Global Constraints

- Ветка `feat/iteration-5-tournaments` (от `main`); Conventional Commits; тесты коммитируются вместе с реализацией или раньше; красные тесты в `main` не попадают.
- TDD (раздел 14): сначала красный приёмочный `TournamentsApiIT` (Task 1), затем внутренний цикл red→green→refactor (домен → application → хранилище → адаптеры).
- `./mvnw verify` — единственная команда проверки; совокупный LINE coverage ≥ 70% (gate).
- Все ID — UUID; время — `Instant`/UTC из внедрённого `Clock`; домен получает `Instant now` аргументом, не `Instant.now()`.
- Enum — VARCHAR + CHECK в миграциях, JPA `EnumType.STRING` не используется (маппинг явный, `.name()`); Hibernate `ddl-auto=validate`; Flyway — единственный источник структуры.
- `version` (optimistic locking, JPA `@Version`) — у агрегатов `Tournament` и `Invitation` (конкурентные accept/старт/отмена); `TournamentEntry`/`Tag` — без version (создаются один раз / низкая конкуренция).
- Границы контекстов — только через `api` и только из адаптеров (ArchUnit, правило уже покрывает `tournaments → {identity, plants, geo}`; новые `identity.api`/`plants.api`-фасады не нарушают правила). `tournaments.domain`/`application` не импортируют чужие контексты; ACL — в `tournaments.adapter.out.{plants,identity}` и `tournaments.adapter.in.events`.
- Отступления «одна tx — один агрегат» (раздел 12): принятие приглашения (Invitation + резерв plants), старт (Tournament + Entry + подтверждение/освобождение резервов), решение модерации → заявка READY в tx `recordDecision` — все описаны в ADR-010 (Task 8) с планом saga лабы №2.
- **Урок итерации 2:** абстрактные базовые классы контрактных тестов репозиториев обязаны нести `@Transactional` на себе.
- Новые понятия — сначала в `docs/domain/glossary.md` (Тег — добавить; Турнир/Приглашение/Участие уже есть), изменения правил — сначала в docs (context map, aggregates, ADR-010), затем код (Task 8 замыкает итерацию, как в итерациях 2–4).
- REST (раздел 13): создание — 201 + Location; чтение/изменение с телом — 200; удаление без тела — 204; page/size по умолчанию 20, диапазон 1–50, вне диапазона → 400 (общий `PaginationParams`); списки с `X-Total-Count`; скрытое — 404, конфликты — 409; уникальные operationId с префиксом контекста.
- Среда: macOS/zsh; рабочая директория `lab1-monolith/`; scratch-файлы диагностики не коммитировать.

## Карта файлов итерации

```text
src/main/java/com/plantarena/
├── identity/
│   ├── api/UserDirectory.java                          НОВОЕ: контракт (UserData вложена)
│   └── application/UserDirectoryFacade.java            НОВОЕ: реализация контракта
├── plants/
│   ├── api/PlantDirectory.java                         НОВОЕ: read-контракт для tournaments
│   └── application/PlantDirectoryFacade.java           НОВОЕ: реализация контракта
├── tournaments/
│   ├── api/
│   │   ├── TournamentData.java                         НОВОЕ: DTO опубликованного контракта
│   │   ├── InvitationData.java                         НОВОЕ
│   │   ├── EntryData.java                              НОВОЕ
│   │   ├── TagData.java                                НОВОЕ
│   │   └── event/
│   │       ├── TournamentStartedEvent.java             НОВОЕ
│   │       └── InvitationCreatedEvent.java             НОВОЕ
│   ├── domain/
│   │   ├── Tournament.java                             НОВОЕ: агрегат, state machine
│   │   ├── TournamentStatus.java                       НОВОЕ: DRAFT/REGISTRATION_OPEN/RUNNING/FINISHED/CANCELLED
│   │   ├── TournamentType.java                         НОВОЕ: PRIVATE (GLOBAL — итерация 7)
│   │   ├── EliminationAlgorithmKind.java               НОВОЕ: ROUND_ELIMINATION
│   │   ├── CancelReason.java                           НОВОЕ: INSUFFICIENT_PARTICIPANTS
│   │   ├── Invitation.java                             НОВОЕ: агрегат, переходы + дедлайн
│   │   ├── InvitationStatus.java                       НОВОЕ: INVITED/ACCEPTED_PENDING_MODERATION/READY/DECLINED/REVOKED/EXPIRED
│   │   ├── TournamentEntry.java                        НОВОЕ: admit при старте
│   │   ├── EntryStatus.java                            НОВОЕ: ACTIVE/ELIMINATED/WINNER
│   │   ├── Tag.java                                    НОВОЕ: справочник
│   │   ├── TournamentRepository.java                   НОВОЕ: порт (search/count/findDueForStart/delete)
│   │   ├── InvitationRepository.java                   НОВОЕ: порт
│   │   ├── TournamentEntryRepository.java              НОВОЕ: порт
│   │   └── TagRepository.java                          НОВОЕ: порт (isUsedByTournament)
│   ├── application/
│   │   ├── TournamentAssembler.java                    НОВОЕ: домен → api DTO (api не зависит от domain)
│   │   ├── TournamentsAccessPolicy.java                НОВОЕ
│   │   ├── TournamentAdministrationService.java        НОВОЕ: create/update/delete/open/cancel
│   │   ├── TournamentQueryService.java                 НОВОЕ: list/get/entries
│   │   ├── InvitationService.java                      НОВОЕ: invite/revoke/accept/decline/list/listMine
│   │   ├── PlantModerationReactionService.java         НОВОЕ: PlantModerationDecided → READY/INVITED
│   │   ├── StartTournamentService.java                 НОВОЕ: start/startDue (один use case)
│   │   ├── TagsService.java                            НОВОЕ: CRUD справочника
│   │   ├── TournamentNotFoundException.java            НОВОЕ (404)
│   │   ├── InvitationNotFoundException.java            НОВОЕ (404, скрыто)
│   │   ├── TagNotFoundException.java                   НОВОЕ (404)
│   │   ├── UnknownUserException.java                   НОВОЕ (404)
│   │   ├── InvitedPlantNotFoundException.java          НОВОЕ (404)
│   │   ├── TournamentStateConflictException.java       НОВОЕ (409)
│   │   ├── RegistrationClosedException.java            НОВОЕ (409, единый язык раздела 13)
│   │   ├── DuplicateInvitationException.java           НОВОЕ (409)
│   │   ├── TagAlreadyExistsException.java              НОВОЕ (409)
│   │   ├── TagInUseException.java                      НОВОЕ (409)
│   │   ├── PlantNotReservableException.java            НОВОЕ (409 + retryAt)
│   │   ├── ImageAlreadyReservedException.java          НОВОЕ (409)
│   │   ├── UnknownStatusFilterException.java           НОВОЕ (400, фильтр статуса списка)
│   │   ├── port/in/CreateTournamentUseCase.java        НОВОЕ
│   │   ├── port/in/UpdateTournamentUseCase.java        НОВОЕ
│   │   ├── port/in/DeleteTournamentUseCase.java        НОВОЕ
│   │   ├── port/in/OpenRegistrationUseCase.java        НОВОЕ
│   │   ├── port/in/CancelTournamentUseCase.java        НОВОЕ
│   │   ├── port/in/StartTournamentUseCase.java         НОВОЕ: start + startDue
│   │   ├── port/in/InviteUserUseCase.java              НОВОЕ
│   │   ├── port/in/RevokeInvitationUseCase.java        НОВОЕ
│   │   ├── port/in/AcceptInvitationUseCase.java        НОВОЕ
│   │   ├── port/in/DeclineInvitationUseCase.java       НОВОЕ
│   │   ├── port/in/ListInvitationsUseCase.java         НОВОЕ: list + listMine
│   │   ├── port/in/ListTournamentsUseCase.java         НОВОЕ
│   │   ├── port/in/GetTournamentUseCase.java           НОВОЕ
│   │   ├── port/in/ListEntriesUseCase.java             НОВОЕ
│   │   ├── port/in/OnPlantModerationDecidedUseCase.java НОВОЕ
│   │   ├── port/in/TagsUseCase.java                    НОВОЕ: CRUD справочника тегов
│   │   └── port/out/
│   │       ├── PlantEligibilityGateway.java            НОВОЕ: reserve/confirm/release
│   │       ├── PlantDirectoryGateway.java              НОВОЕ: PlantSnapshot(ownerId, approved)
│   │       └── ParticipantDirectoryGateway.java        НОВОЕ: isKnownUser
│   └── adapter/
│       ├── in/web/
│       │   ├── TournamentController.java               НОВОЕ: /api/v1/tournaments*
│       │   ├── InvitationController.java               НОВОЕ: /me/invitations, /invitations/*
│       │   ├── TagController.java                      НОВОЕ: /api/v1/tags*
│       │   ├── DemoJobsController.java                 НОВОЕ: /internal/demo/jobs/run-due (dev/test)
│       │   ├── TournamentsExceptionHandler.java        НОВОЕ
│       │   ├── CreateTournamentRequest.java            НОВОЕ
│       │   ├── UpdateTournamentRequest.java            НОВОЕ
│       │   ├── TournamentResponse.java                 НОВОЕ
│       │   ├── InvitationResponse.java                 НОВОЕ
│       │   ├── EntryResponse.java                      НОВОЕ
│       │   ├── AcceptInvitationRequest.java            НОВОЕ
│       │   ├── InviteUserRequest.java                  НОВОЕ
│       │   ├── CreateTagRequest.java                   НОВОЕ
│       │   ├── UpdateTagRequest.java                   НОВОЕ
│       │   └── TagResponse.java                        НОВОЕ
│       ├── in/events/PlantModerationDecidedHandler.java НОВОЕ: @EventListener
│       ├── in/jobs/TournamentDeadlinePoller.java       НОВОЕ: @Scheduled(fixedDelay=2с)
│       ├── out/plants/
│       │   ├── InProcessPlantEligibility.java          НОВОЕ: ACL plants.api.PlantEligibility
│       │   └── InProcessPlantDirectory.java            НОВОЕ: ACL plants.api.PlantDirectory
│       ├── out/identity/InProcessParticipantDirectory.java НОВОЕ: ACL identity.api.UserDirectory
│       └── out/persistence/
│           ├── TournamentJpaEntity.java                НОВОЕ (@ManyToMany tags)
│           ├── TournamentJpaRepository.java            НОВОЕ (search/count/findDueForStart)
│           ├── JpaTournamentRepository.java            НОВОЕ
│           ├── InvitationJpaEntity.java                НОВОЕ
│           ├── InvitationJpaRepository.java            НОВОЕ
│           ├── JpaInvitationRepository.java            НОВОЕ
│           ├── TournamentEntryJpaEntity.java           НОВОЕ
│           ├── TournamentEntryJpaRepository.java       НОВОЕ
│           ├── JpaTournamentEntryRepository.java       НОВОЕ
│           ├── TagJpaEntity.java                       НОВОЕ
│           ├── TagJpaRepository.java                   НОВОЕ
│           └── JpaTagRepository.java                   НОВОЕ
└── resources/db/migration/tournaments/V2__tournaments.sql  НОВОЕ (V1__init.sql — placeholder)

src/test/java/com/plantarena/
├── identity/application/UserDirectoryFacadeTest.java   НОВОЕ (Task 1)
├── plants/application/PlantDirectoryFacadeTest.java    НОВОЕ (Task 1)
├── tournaments/
│   ├── TournamentsApiIT.java                           НОВОЕ (Task 1, красный; зелёный в Task 7)
│   ├── domain/
│   │   ├── TournamentTest.java                         НОВОЕ (Task 2)
│   │   ├── TagTest.java                                НОВОЕ (Task 2)
│   │   ├── InvitationTest.java                         НОВОЕ (Task 3)
│   │   └── TournamentEntryTest.java                    НОВОЕ (Task 3)
│   ├── application/
│   │   ├── TagsServiceTest.java                        НОВОЕ (Task 4)
│   │   ├── TournamentAdministrationServiceTest.java    НОВОЕ (Task 4)
│   │   ├── TournamentQueryServiceTest.java             НОВОЕ (Task 4)
│   │   ├── InvitationServiceTest.java                  НОВОЕ (Task 5)
│   │   ├── PlantModerationReactionServiceTest.java     НОВОЕ (Task 5)
│   │   ├── StartTournamentServiceTest.java             НОВОЕ (Task 5)
│   │   └── support/                                    НОВОЕ (Tasks 4–5): InMemory{Tournament,
│   │       Invitation,TournamentEntry,Tag}Repository, Fake{PlantEligibility,
│   │       PlantDirectory,ParticipantDirectory}Gateway, FakeEventPublisher
│   ├── TournamentRepositoryContractTest.java           НОВОЕ (Task 6, абстрактный)
│   ├── InvitationRepositoryContractTest.java           НОВОЕ (Task 6, абстрактный)
│   ├── TournamentEntryRepositoryContractTest.java      НОВОЕ (Task 6, абстрактный)
│   ├── TagRepositoryContractTest.java                  НОВОЕ (Task 6, абстрактный)
│   └── adapter/out/persistence/
│       ├── JpaTournamentRepositoryContractIT.java      НОВОЕ (Task 6)
│       ├── JpaInvitationRepositoryContractIT.java      НОВОЕ (Task 6)
│       ├── JpaTournamentEntryRepositoryContractIT.java НОВОЕ (Task 6)
│       └── JpaTagRepositoryContractIT.java             НОВОЕ (Task 6)

docs/domain/adr/ADR-010-tournament-cross-context-tx.md  НОВОЕ (Task 8)
docs/domain/{glossary,aggregates,context-map}.md, README.md  ИЗМЕНЕНО (Task 8)
docs/specs/2026-09-27-iteration-5-tournaments-design.md  СОЗДАН до плана
```

---

### Task 1: Контракты plants.PlantDirectory и identity.UserDirectory, порты out tournaments, красный TournamentsApiIT

**Files:**
- Create: `src/main/java/com/plantarena/plants/api/PlantDirectory.java`
- Create: `src/main/java/com/plantarena/plants/application/PlantDirectoryFacade.java`
- Create: `src/main/java/com/plantarena/identity/api/UserDirectory.java`
- Create: `src/main/java/com/plantarena/identity/application/UserDirectoryFacade.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/out/PlantEligibilityGateway.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/out/PlantDirectoryGateway.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/out/ParticipantDirectoryGateway.java`
- Test: `src/test/java/com/plantarena/plants/application/PlantDirectoryFacadeTest.java`
- Test: `src/test/java/com/plantarena/identity/application/UserDirectoryFacadeTest.java`
- Test: `src/test/java/com/plantarena/tournaments/TournamentsApiIT.java`

**Interfaces:**
- Consumes: `PlantRepository.findById` (существует), `PlantData` (существует), `UserRepository.findById` (существует), REST итераций 1–4 (`/api/v1/users`, `/files`, `/plants`), `AbstractIntegrationTest`, эталонные `green-8x8.png`/`red-8x8.png`, `DeterministicPlantClassifier` (существует в `moderation.support`).
- Produces (для Tasks 2–7): `plants.api.PlantDirectory.findById(UUID) → Optional<PlantData>`; `identity.api.UserDirectory.findById(UUID) → Optional<UserDirectory.UserData>`, `record UserData(UUID id, String displayName, boolean active)`; порты `PlantEligibilityGateway { UUID reserve(UUID ownerId, UUID plantId, UUID idempotencyKey); void confirm(UUID ownerId, UUID plantId, UUID reservationId); void release(UUID reservationId); }`, `PlantDirectoryGateway { Optional<PlantSnapshot> findById(UUID plantId); record PlantSnapshot(UUID plantId, UUID ownerId, boolean approved) }`, `ParticipantDirectoryGateway { boolean isKnownUser(UUID userId); }`; красный `TournamentsApiIT` (зелёный в Task 7).

- [ ] **Step 1: Красные тесты фасадов plants и identity**

`src/test/java/com/plantarena/plants/application/PlantDirectoryFacadeTest.java`:

```java
package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantData;
import com.plantarena.plants.application.support.InMemoryPlantRepository;
import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ImageFormat;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Контракт PlantDirectory: данные растения для внутреннего потребителя (tournaments)")
class PlantDirectoryFacadeTest {

    private final InMemoryPlantRepository repository = new InMemoryPlantRepository();
    private final PlantDirectoryFacade facade = new PlantDirectoryFacade(repository);

    @Test
    @DisplayName("findById возвращает PlantData без проверок прав (внутренний контракт монолита)")
    void find_by_id_возвращает_данные() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submitPlant(ownerId, ModerationStatus.APPROVED);

        PlantData data = facade.findById(plantId).orElseThrow();

        assertThat(data.ownerId()).isEqualTo(ownerId);
        assertThat(data.moderationStatus().name()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("неизвестный id — пустой результат")
    void неизвестный_id_пустой_результат() {
        assertThat(facade.findById(UUID.randomUUID())).isEmpty();
    }

    private UUID submitPlant(UUID ownerId, ModerationStatus status) {
        Plant plant = Plant.submit(ownerId, UUID.randomUUID(),
            new ImageFingerprint("a".repeat(64), 1), ImageFormat.PNG, "Фикус",
            Instant.parse("2026-09-27T10:00:00Z"));
        plant.applyDecision(status, "причина");
        repository.save(plant);
        return plant.id();
    }
}
```

Проверить перед реализацией: `Plant.submit(ownerId, assetId, fingerprint, format, title, createdAt)` и `plant.applyDecision(status, reason)` — сигнатуры итерации 3 (см. `PlantService`/`PlantModerationService`); `InMemoryPlantRepository` существует в `plants.application.support`.

`src/test/java/com/plantarena/identity/application/UserDirectoryFacadeTest.java`:

```java
package com.plantarena.identity.application;

import com.plantarena.identity.application.support.InMemoryUserRepository;
import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.PasswordHasher;
import com.plantarena.identity.domain.User;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Контракт UserDirectory: публичный профиль для внутреннего потребителя (tournaments)")
class UserDirectoryFacadeTest {

    private final InMemoryUserRepository repository = new InMemoryUserRepository();
    private final UserDirectoryFacade facade = new UserDirectoryFacade(repository);

    @Test
    @DisplayName("findById возвращает id, имя и активность без email и passwordHash")
    void find_by_id_возвращает_публичный_профиль() {
        User user = User.registerUser(Email.of("u@example.com"), "U", "hash");
        repository.save(user);

        UserDirectory.UserData data = facade.findById(user.id()).orElseThrow();

        assertThat(data.id()).isEqualTo(user.id());
        assertThat(data.displayName()).isEqualTo("U");
        assertThat(data.active()).isTrue();
    }

    @Test
    @DisplayName("неизвестный id — пустой результат")
    void неизвестный_id_пустой_результат() {
        assertThat(facade.findById(UUID.randomUUID())).isEmpty();
    }
}
```

Проверить перед реализацией: `Email.of(...)`/`User.registerUser(email, displayName, passwordHash)` — сигнатуры итерации 1 (см. `UserAdministrationServiceTest`); `InMemoryUserRepository` существует.

- [ ] **Step 2: Запустить — красный**

```bash
./mvnw -q test -Dtest='PlantDirectoryFacadeTest,UserDirectoryFacadeTest'
```

Ожидание: FAIL (компиляция): `cannot find symbol: class PlantDirectoryFacade` / `class UserDirectoryFacade`.

- [ ] **Step 3: Контракты и фасады**

`src/main/java/com/plantarena/plants/api/PlantDirectory.java`:

```java
package com.plantarena.plants.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Опубликованный read-контракт plants для tournaments (раздел 4.3): данные
 * растения по id. Права не проверяются — внутренний контракт монолита
 * (как MediaAssets.loadContent для moderation, итерация 4): вызов идёт по
 * plantId из приглашения, видимость решает tournaments.
 */
public interface PlantDirectory {

    Optional<PlantData> findById(UUID plantId);
}
```

`src/main/java/com/plantarena/plants/application/PlantDirectoryFacade.java`:

```java
package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantData;
import com.plantarena.plants.api.PlantDirectory;
import com.plantarena.plants.api.PlantLifeStatus;
import com.plantarena.plants.api.PlantModerationStatus;
import com.plantarena.plants.application.port.out.PlantRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация read-контракта PlantDirectory: маппинг домена в api-DTO без
 * проверок прав (внутренний потребитель — tournaments, итерация 5).
 */
@Service
@Transactional(readOnly = true)
public class PlantDirectoryFacade implements PlantDirectory {

    private final PlantRepository plants;

    public PlantDirectoryFacade(PlantRepository plants) {
        this.plants = plants;
    }

    @Override
    public Optional<PlantData> findById(UUID plantId) {
        return plants.findById(plantId)
            .map(plant -> new PlantData(plant.id(), plant.ownerId(), plant.assetId(),
                plant.title(),
                PlantModerationStatus.valueOf(plant.moderationStatus().name()),
                PlantLifeStatus.valueOf(plant.lifeStatus().name()),
                plant.createdAt(), plant.diedAt(), plant.archivedAt()));
    }
}
```

`src/main/java/com/plantarena/identity/api/UserDirectory.java`:

```java
package com.plantarena.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Опубликованный контракт identity (Open Host Service, раздел 4.3): публичный
 * профиль пользователя для внутренних потребителей (tournaments — проверка
 * адресата приглашения, итерация 5). email и passwordHash не раскрываются.
 */
public interface UserDirectory {

    Optional<UserData> findById(UUID userId);

    /** Публичный профиль: id, имя, активность. */
    record UserData(UUID id, String displayName, boolean active) {
    }
}
```

`src/main/java/com/plantarena/identity/application/UserDirectoryFacade.java`:

```java
package com.plantarena.identity.application;

import com.plantarena.identity.api.UserDirectory;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import com.plantarena.identity.domain.UserStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация контракта UserDirectory: публичный профиль без email и пароля.
 */
@Service
@Transactional(readOnly = true)
public class UserDirectoryFacade implements UserDirectory {

    private final UserRepository users;

    public UserDirectoryFacade(UserRepository users) {
        this.users = users;
    }

    @Override
    public Optional<UserData> findById(UUID userId) {
        return users.findById(userId)
            .map(user -> new UserData(user.id(), user.displayName(),
                user.status() == UserStatus.ACTIVE));
    }
}
```

- [ ] **Step 4: Запустить — зелёный**

```bash
./mvnw -q test -Dtest='PlantDirectoryFacadeTest,UserDirectoryFacadeTest'
```

Ожидание: PASS (4 теста). Затем `./mvnw -q verify -Dit.test='MediaApiIT,PlantsApiIT,ModerationApiIT' -DfailIfNoTests=false` — существующие IT не сломаны.

- [ ] **Step 5: Порты out tournaments**

`src/main/java/com/plantarena/tournaments/application/port/out/PlantEligibilityGateway.java`:

```java
package com.plantarena.tournaments.application.port.out;

import java.util.UUID;

/**
 * Выходной порт tournaments: допуск растения (раздел 6, Consumer-driven).
 * Адаптер tournaments.adapter.out.plants вызывает plants.api.PlantEligibility
 * и переводит его исключения в исключения tournaments (ACL, раздел 4.3).
 */
public interface PlantEligibilityGateway {

    /**
     * Зарезервировать изображение за заявкой (fresh idempotency key на каждую
     * попытку принятия приглашения — дизайн итерации 5, решение 3).
     *
     * @throws com.plantarena.tournaments.application.PlantNotReservableException
     *         растение не проходит проверки plants (не владелец/погибло/запрет/не APPROVED)
     * @throws com.plantarena.tournaments.application.ImageAlreadyReservedException
     *         изображение уже активно зарезервировано другой заявкой
     */
    UUID reserve(UUID ownerId, UUID plantId, UUID idempotencyKey);

    /**
     * Подтвердить допуск к старту: только APPROVED и действующий резерв этой заявки.
     *
     * @throws com.plantarena.tournaments.application.PlantNotReservableException проверки не пройдены
     */
    void confirm(UUID ownerId, UUID plantId, UUID reservationId);

    /** Освободить резерв (отказ/отмена/EXPIRED). Идемпотентно. */
    void release(UUID reservationId);
}
```

`src/main/java/com/plantarena/tournaments/application/port/out/PlantDirectoryGateway.java`:

```java
package com.plantarena.tournaments.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Выходной порт tournaments: данные растения для проверки APPROVED при
 * принятии приглашения (PENDING → ACCEPTED_PENDING_MODERATION, APPROVED →
 * сразу READY). Адаптер — ACL над plants.api.PlantDirectory.
 */
public interface PlantDirectoryGateway {

    Optional<PlantSnapshot> findById(UUID plantId);

    /** Минимальный снимок растения: владелец и факт одобрения модерацией. */
    record PlantSnapshot(UUID plantId, UUID ownerId, boolean approved) {
    }
}
```

`src/main/java/com/plantarena/tournaments/application/port/out/ParticipantDirectoryGateway.java`:

```java
package com.plantarena.tournaments.application.port.out;

import java.util.UUID;

/**
 * Выходной порт tournaments: проверка известного активного пользователя при
 * приглашении. Адаптер — ACL над identity.api.UserDirectory.
 */
public interface ParticipantDirectoryGateway {

    boolean isKnownUser(UUID userId);
}
```

- [ ] **Step 6: Красный приёмочный TournamentsApiIT**

`src/test/java/com/plantarena/tournaments/TournamentsApiIT.java`:

```java
package com.plantarena.tournaments;

import com.jayway.jsonpath.JsonPath;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.support.DeterministicPlantClassifier;
import com.plantarena.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Внешний цикл TDD итерации 5: сценарии раздела 7 (закрытый турнир) через
 * HTTP на Testcontainers. Классификатор детерминированный (как в
 * ModerationApiIT): зелёный PNG → APPROVED → приглашение READY по событию
 * PlantModerationDecided; красный → REJECTED → возврат в INVITED. Дедлайны
 * короткие (секунды), старт по дедлайну выполняет @Scheduled-poller (2с) —
 * ждём Awaitility. Красный до Task 7 (контроллеров нет).
 */
@DisplayName("Сценарии раздела 7 (закрытый турнир): черновик → приглашения → заявки → старт")
class TournamentsApiIT extends AbstractIntegrationTest {

    private static final String DEMO_HEADER = "X-Demo-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TestConfiguration
    static class DeterministicClassifierConfig {

        @Bean
        @Primary
        PlantClassifier deterministicPlantClassifier() {
            return new DeterministicPlantClassifier();
        }
    }

    @Test
    @DisplayName("полный цикл: черновик → приглашения → регистрация → заявки → дедлайн → RUNNING с участниками")
    void полный_цикл_до_running() throws Exception {
        UUID organizer = adminId();
        UUID u1 = createUserAsAdmin("tour-u1@example.com", "U1");
        UUID u2 = createUserAsAdmin("tour-u2@example.com", "U2");
        UUID u3 = createUserAsAdmin("tour-u3@example.com", "U3");
        UUID u4 = createUserAsAdmin("tour-u4@example.com", "U4");
        UUID stranger = createUserAsAdmin("tour-stranger@example.com", "Stranger");
        UUID tagId = createTagAsAdmin("Комнатные");

        UUID tournamentId = createTournamentAsAdmin("Осенний чемпионат",
            Instant.now().plusSeconds(15), tagId);
        inviteAsAdmin(tournamentId, u1);
        inviteAsAdmin(tournamentId, u2);
        inviteAsAdmin(tournamentId, u3);
        inviteAsAdmin(tournamentId, u4);

        // дубль приглашения — 409 (уникальность пары турнир/пользователь)
        mockMvc.perform(post("/api/v1/tournaments/" + tournamentId + "/invitations")
                .header(DEMO_HEADER, organizer.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\"}".formatted(u1)))
            .andExpect(status().isConflict());

        // приглашённый видит турнир в списке, чужой — нет (раздел 13: только доступные)
        assertThat(tournamentIdsVisibleTo(u1)).contains(tournamentId);
        assertThat(tournamentIdsVisibleTo(stranger)).doesNotContain(tournamentId);
        mockMvc.perform(get("/api/v1/tournaments/" + tournamentId)
                .header(DEMO_HEADER, stranger.toString()))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/tournaments/" + tournamentId + "/open-registration")
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REGISTRATION_OPEN"));

        // параметры после открытия не меняются (409), описание — безопасное (200)
        mockMvc.perform(patch("/api/v1/tournaments/" + tournamentId)
                .header(DEMO_HEADER, organizer.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"minParticipants\":3}"))
            .andExpect(status().isConflict());
        mockMvc.perform(patch("/api/v1/tournaments/" + tournamentId)
                .header(DEMO_HEADER, organizer.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"Описание после открытия\"}"))
            .andExpect(status().isOk());

        // черновик с историей не удаляется; старт до дедлайна запрещён
        mockMvc.perform(delete("/api/v1/tournaments/" + tournamentId)
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/tournaments/" + tournamentId + "/start")
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(status().isConflict());

        // u1: зелёное растение → ACCEPTED_PENDING_MODERATION → READY по событию
        UUID plant1 = greenPlantOf(u1, "Фикус u1");
        UUID invitation1 = acceptInvitation(u1, tournamentId, plant1);
        awaitInvitationStatus(u1, invitation1, "READY");

        // u2: то же — второй READY
        UUID plant2 = greenPlantOf(u2, "Фикус u2");
        UUID invitation2 = acceptInvitation(u2, tournamentId, plant2);
        awaitInvitationStatus(u2, invitation2, "READY");

        // u3: красное растение → REJECTED → возврат в INVITED, затем отказ
        UUID plant3 = redPlantOf(u3, "Кот в горшке");
        UUID invitation3 = acceptInvitation(u3, tournamentId, plant3);
        awaitInvitationStatus(u3, invitation3, "INVITED");
        mockMvc.perform(post("/api/v1/invitations/" + invitation3 + "/decline")
                .header(DEMO_HEADER, u3.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DECLINED"));

        // u4 не принял — EXPIRED после старта; u3 DECLINED не меняется
        // дедлайн прошёл → scheduler стартует тот же use case, что и ручка
        awaitTournamentStatus(tournamentId, "RUNNING");

        mockMvc.perform(get("/api/v1/tournaments/" + tournamentId + "/entries")
                .header(DEMO_HEADER, u1.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total-Count", "2"))
            .andExpect(jsonPath("$.length()").value(2));

        UUID invitation4 = myInvitationsOf(u4).get(0).id();
        assertThat(statusOfInvitation(u4, invitation4)).isEqualTo("EXPIRED");
        assertThat(statusOfInvitation(u3, invitation3)).isEqualTo("DECLINED");

        // отмена активного турнира запрещена (первый вариант, раздел 7)
        mockMvc.perform(post("/api/v1/tournaments/" + tournamentId + "/cancel")
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("недостаток участников: CANCELLED + INSUFFICIENT_PARTICIPANTS, резервы освобождены, accept после дедлайна 409")
    void недостаток_участников_отмена() throws Exception {
        UUID organizer = adminId();
        UUID u1 = createUserAsAdmin("ins-u1@example.com", "Ins U1");
        UUID tournamentId = createTournamentAsAdmin("Недостаток",
            Instant.now().plusSeconds(6), null);
        inviteAsAdmin(tournamentId, u1);
        mockMvc.perform(post("/api/v1/tournaments/" + tournamentId + "/open-registration")
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(status().isOk());

        UUID plantId = greenPlantOf(u1, "Единственный фикус");
        UUID invitationId = acceptInvitation(u1, tournamentId, plantId);
        awaitInvitationStatus(u1, invitationId, "READY");

        // один READY < minParticipants=2 → автоматическая отмена по дедлайну
        awaitTournamentStatus(tournamentId, "CANCELLED");
        mockMvc.perform(get("/api/v1/tournaments/" + tournamentId)
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancelReason").value("INSUFFICIENT_PARTICIPANTS"));

        // резерв освобождён: растение можно архивировать (вне активного резерва)
        mockMvc.perform(delete("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, u1.toString()))
            .andExpect(status().isNoContent());

        // после дедлайна/отмены принять приглашение нельзя
        mockMvc.perform(post("/api/v1/invitations/" + invitationId + "/accept")
                .header(DEMO_HEADER, u1.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plantId)))
            .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("отзыв приглашения: только не принятое; чужое приглашение скрыто; чужое растение не резервируется")
    void отзыв_и_доступ_к_приглашениям() throws Exception {
        UUID organizer = adminId();
        UUID u1 = createUserAsAdmin("rev-u1@example.com", "Rev U1");
        UUID u2 = createUserAsAdmin("rev-u2@example.com", "Rev U2");
        UUID tournamentId = createTournamentAsAdmin("Отзывы",
            Instant.now().plusSeconds(60), null);
        UUID invitation1 = inviteAsAdmin(tournamentId, u1);
        UUID invitation2 = inviteAsAdmin(tournamentId, u2);
        mockMvc.perform(post("/api/v1/tournaments/" + tournamentId + "/open-registration")
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(status().isOk());

        // u1 принял — отзывать принятое нельзя (409)
        UUID plantId = greenPlantOf(u1, "Фикус для отзыва");
        acceptInvitation(u1, tournamentId, plantId);
        mockMvc.perform(delete("/api/v1/tournaments/" + tournamentId
                + "/invitations/" + invitation1)
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(status().isConflict());

        // не принятое приглашение отзывается (REVOKED), принять его больше нельзя
        mockMvc.perform(delete("/api/v1/tournaments/" + tournamentId
                + "/invitations/" + invitation2)
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/invitations/" + invitation2 + "/accept")
                .header(DEMO_HEADER, u2.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plantId)))
            .andExpect(status().isConflict());

        // чужое приглашение скрыто (404), чужое растение не резервируется (409)
        mockMvc.perform(post("/api/v1/invitations/" + invitation1 + "/accept")
                .header(DEMO_HEADER, u2.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plantId)))
            .andExpect(status().isNotFound());
        UUID strangerPlant = greenPlantOf(u2, "Чужой фикус");
        mockMvc.perform(post("/api/v1/invitations/" + invitation1 + "/accept")
                .header(DEMO_HEADER, u1.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(strangerPlant)))
            .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("теги: CRUD, дубль имени 409, удаление используемого 409, фильтр по тегу")
    void теги_справочник() throws Exception {
        UUID organizer = adminId();
        UUID tagId = createTagAsAdmin("Суккуленты");
        mockMvc.perform(post("/api/v1/tags")
                .header(DEMO_HEADER, organizer.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Суккуленты\"}"))
            .andExpect(status().isConflict()); // дубль имени

        mockMvc.perform(patch("/api/v1/tags/" + tagId)
                .header(DEMO_HEADER, organizer.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Кактусы\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Кактусы"));

        mockMvc.perform(get("/api/v1/tags")
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Total-Count"));

        UUID tournamentId = createTournamentAsAdmin("Тегированный",
            Instant.now().plusSeconds(120), tagId);
        mockMvc.perform(delete("/api/v1/tags/" + tagId)
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(status().isConflict()); // используется турниром

        mockMvc.perform(get("/api/v1/tournaments?tagId=" + tagId)
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total-Count", "1"));

        // пустой черновик удаляется; свободный тег удаляется
        UUID emptyDraft = createTournamentAsAdmin("Пустой черновик",
            Instant.now().plusSeconds(120), null);
        mockMvc.perform(delete("/api/v1/tournaments/" + emptyDraft)
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(status().isNoContent());
        UUID freeTag = createTagAsAdmin("Свободный тег");
        mockMvc.perform(delete("/api/v1/tags/" + freeTag)
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("гость и обычный пользователь: 401/403 на защищённых ручках")
    void доступ_гостя_и_не_организатора() throws Exception {
        UUID user = createUserAsAdmin("plain-u@example.com", "Plain");

        mockMvc.perform(get("/api/v1/tournaments"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/tournaments")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(tournamentBody("Чужой черновик", Instant.now().plusSeconds(120), null)))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/tags")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Нельзя\"}"))
            .andExpect(status().isForbidden());
    }

    // ---------- helpers ----------

    private record MyInvitation(UUID id, String status) {
    }

    private List<MyInvitation> myInvitationsOf(UUID user) throws Exception {
        String body = mockMvc.perform(get("/api/v1/me/invitations")
                .header(DEMO_HEADER, user.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<?> items = JsonPath.read(body, "$");
        return items.stream()
            .map(item -> {
                @SuppressWarnings("unchecked")
                var map = (java.util.Map<String, Object>) item;
                return new MyInvitation(UUID.fromString((String) map.get("id")),
                    (String) map.get("status"));
            })
            .toList();
    }

    private String statusOfInvitation(UUID user, UUID invitationId) throws Exception {
        return myInvitationsOf(user).stream()
            .filter(invitation -> invitation.id().equals(invitationId))
            .findFirst().orElseThrow().status();
    }

    private List<UUID> tournamentIdsVisibleTo(UUID user) throws Exception {
        String body = mockMvc.perform(get("/api/v1/tournaments")
                .header(DEMO_HEADER, user.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return ((List<String>) JsonPath.read(body, "$[*].id")).stream()
            .map(UUID::fromString).toList();
    }

    private UUID createTournamentAsAdmin(String name, Instant deadline, UUID tagId)
            throws Exception {
        String body = mockMvc.perform(post("/api/v1/tournaments")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(tournamentBody(name, deadline, tagId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private String tournamentBody(String name, Instant deadline, UUID tagId) {
        String tags = tagId == null ? "[]" : "[\"" + tagId + "\"]";
        return """
            {"name":"%s","description":"Описание","registrationDeadline":"%s",\
            "roundDurationSeconds":3600,"eliminationFraction":0.5,\
            "minParticipants":2,"tagIds":%s}
            """.formatted(name, deadline, tags);
    }

    private UUID inviteAsAdmin(UUID tournamentId, UUID userId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/tournaments/" + tournamentId
                + "/invitations")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\"}".formatted(userId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID acceptInvitation(UUID user, UUID tournamentId, UUID plantId)
            throws Exception {
        UUID invitationId = myInvitationsOf(user).stream()
            .filter(invitation -> "INVITED".equals(invitation.status()))
            .findFirst().orElseThrow()
            .id();
        mockMvc.perform(post("/api/v1/invitations/" + invitationId + "/accept")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plantId)))
            .andExpect(status().isOk());
        return invitationId;
    }

    private UUID createTagAsAdmin(String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/tags")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\"}".formatted(name)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID greenPlantOf(UUID user, String title) throws Exception {
        return plantOf(user, title, "green-8x8.png");
    }

    private UUID redPlantOf(UUID user, String title) throws Exception {
        return plantOf(user, title, "red-8x8.png");
    }

    private UUID plantOf(UUID user, String title, String reference) throws Exception {
        UUID assetId = uploadAs(user, referenceBytes(reference));
        String body = mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":\"%s\",\"title\":\"%s\"}".formatted(assetId, title)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID uploadAs(UUID userId, byte[] content) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("file", "reference.png",
                    MediaType.IMAGE_PNG_VALUE, content))
                .header(DEMO_HEADER, userId.toString()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private void awaitInvitationStatus(UUID user, UUID invitationId, String expected) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(statusOfInvitation(user, invitationId))
                .isEqualTo(expected));
    }

    private void awaitTournamentStatus(UUID tournamentId, String expected) {
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> mockMvc.perform(
                    get("/api/v1/tournaments/" + tournamentId)
                        .header(DEMO_HEADER, adminId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expected)));
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
}
```

- [ ] **Step 7: Запустить — красный**

```bash
./mvnw -q verify -Dit.test=TournamentsApiIT -DfailIfNoTests=false
```

Ожидание: unit-тесты PASS; `TournamentsApiIT` FAIL: все сценарии падают на `createTagAsAdmin`/`createTournamentAsAdmin` — POST `/api/v1/tags` и `/api/v1/tournaments` дают 404 (контроллеров нет). Это честный красный внешнего цикла.

- [ ] **Step 8: Commit**

```bash
git checkout -b feat/iteration-5-tournaments
git add docs/specs/2026-09-27-iteration-5-tournaments-design.md \
  src/main/java/com/plantarena/plants/api/PlantDirectory.java \
  src/main/java/com/plantarena/plants/application/PlantDirectoryFacade.java \
  src/main/java/com/plantarena/identity/api/UserDirectory.java \
  src/main/java/com/plantarena/identity/application/UserDirectoryFacade.java \
  src/main/java/com/plantarena/tournaments/application/port/out \
  src/test/java/com/plantarena/plants/application/PlantDirectoryFacadeTest.java \
  src/test/java/com/plantarena/identity/application/UserDirectoryFacadeTest.java \
  src/test/java/com/plantarena/tournaments/TournamentsApiIT.java
git commit -m "feat(tournaments): контракты plants.PlantDirectory и identity.UserDirectory, порты out, красный TournamentsApiIT"
```

---

### Task 2: Домен Tournament и Tag — state machine и параметры

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/domain/TournamentStatus.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/TournamentType.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/EliminationAlgorithmKind.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/CancelReason.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/Tournament.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/TournamentRepository.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/Tag.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/TagRepository.java`
- Test: `src/test/java/com/plantarena/tournaments/domain/TournamentTest.java`
- Test: `src/test/java/com/plantarena/tournaments/domain/TagTest.java`

**Interfaces:**
- Consumes: ничего (чистая Java).
- Produces (для Tasks 4–7): `Tournament.createDraft(creatorId, name, description, registrationDeadline, roundDuration, eliminationFraction, minParticipants, tagIds, now)`, `restore(id, creatorId, name, description, type, status, algorithm, registrationDeadline, roundDuration, eliminationFraction, minParticipants, cancelReason, tagIds, createdAt, version)`, `openRegistration(now)`, `start(now, readyCount)`, `cancel(now)`, `cancelForInsufficientParticipants(now)`, `updateParameters(name, registrationDeadline, roundDuration, eliminationFraction, minParticipants, now)`, `updateDescription(description)`, `replaceTags(tagIds)`, `canInvite(now)`, `isAcceptingNow(now)`, геттеры `id()/creatorId()/name()/description()/type()/status()/algorithm()/registrationDeadline()/roundDuration()/eliminationFraction()/minParticipants()/cancelReason()/tagIds()/createdAt()/version()`; enum'ы `TournamentStatus {DRAFT, REGISTRATION_OPEN, RUNNING, FINISHED, CANCELLED}`, `TournamentType {PRIVATE}`, `EliminationAlgorithmKind {ROUND_ELIMINATION}`, `CancelReason {INSUFFICIENT_PARTICIPANTS}`; `Tag.create(name, now)`, `restore(id, name, createdAt)`, `rename(name)`, геттеры `id()/name()/createdAt()`; порты `TournamentRepository {save, findById, delete, search, count, findDueForStart}` c `record TournamentFilter(UUID userId, boolean admin, TournamentStatus status, UUID tagId, int offset, int size)` и `TagRepository {save, findById, findByName, findAll, count, delete, isUsedByTournament}`.

- [ ] **Step 1: Красный доменный тест Tournament**

`src/test/java/com/plantarena/tournaments/domain/TournamentTest.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Агрегат Tournament: state machine DRAFT → REGISTRATION_OPEN → RUNNING → FINISHED, отмена до RUNNING")
class TournamentTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(3600);

    private Tournament draft() {
        return Tournament.createDraft(UUID.randomUUID(), "Осенний чемпионат", "Описание",
            DEADLINE, Duration.ofHours(1), 0.5, 2, Set.of(), NOW);
    }

    @Test
    @DisplayName("фабрика: DRAFT, PRIVATE, ROUND_ELIMINATION, параметры сохранены")
    void фабрика_draft() {
        UUID creatorId = UUID.randomUUID();
        UUID tagId = UUID.randomUUID();
        Tournament tournament = Tournament.createDraft(creatorId, "  Название  ", "Описание",
            DEADLINE, Duration.ofHours(1), 0.25, 3, Set.of(tagId), NOW);

        assertThat(tournament.status()).isEqualTo(TournamentStatus.DRAFT);
        assertThat(tournament.type()).isEqualTo(TournamentType.PRIVATE);
        assertThat(tournament.algorithm()).isEqualTo(EliminationAlgorithmKind.ROUND_ELIMINATION);
        assertThat(tournament.name()).isEqualTo("Название"); // trim
        assertThat(tournament.creatorId()).isEqualTo(creatorId);
        assertThat(tournament.tagIds()).containsExactly(tagId);
        assertThat(tournament.cancelReason()).isNull();
        assertThat(tournament.createdAt()).isEqualTo(NOW);
        assertThat(tournament.version()).isZero();
    }

    @Test
    @DisplayName("параметры: дедлайн в прошлом, доля 0/1, minParticipants < 2, пустое имя — запрещены")
    void параметры_инварианты() {
        UUID creatorId = UUID.randomUUID();
        assertThatThrownBy(() -> Tournament.createDraft(creatorId, "Н", null,
            NOW.minusSeconds(1), Duration.ofHours(1), 0.5, 2, Set.of(), NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tournament.createDraft(creatorId, "Н", null,
            DEADLINE, Duration.ofHours(1), 0d, 2, Set.of(), NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tournament.createDraft(creatorId, "Н", null,
            DEADLINE, Duration.ofHours(1), 1d, 2, Set.of(), NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tournament.createDraft(creatorId, "Н", null,
            DEADLINE, Duration.ofHours(1), 0.5, 1, Set.of(), NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tournament.createDraft(creatorId, " ", null,
            DEADLINE, Duration.ofHours(1), 0.5, 2, Set.of(), NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tournament.createDraft(creatorId, "Н", null,
            DEADLINE, Duration.ZERO, 0.5, 2, Set.of(), NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("openRegistration: DRAFT → REGISTRATION_OPEN; после дедлайна запрещено")
    void open_registration() {
        Tournament tournament = draft();
        tournament.openRegistration(NOW);
        assertThat(tournament.status()).isEqualTo(TournamentStatus.REGISTRATION_OPEN);

        assertThatThrownBy(() -> draft().openRegistration(DEADLINE))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tournament.openRegistration(NOW))
            .isInstanceOf(IllegalStateException.class); // уже открыта
    }

    @Test
    @DisplayName("start: REGISTRATION_OPEN + now ≥ deadline + READY ≥ minParticipants → RUNNING")
    void start_переход() {
        Tournament tournament = draft();
        assertThatThrownBy(() -> tournament.start(DEADLINE, 2))
            .isInstanceOf(IllegalStateException.class); // ещё DRAFT

        tournament.openRegistration(NOW);
        assertThatThrownBy(() -> tournament.start(NOW, 2))
            .isInstanceOf(IllegalStateException.class); // до дедлайна
        assertThatThrownBy(() -> tournament.start(DEADLINE, 1))
            .isInstanceOf(IllegalStateException.class); // меньше участников

        tournament.start(DEADLINE, 2);
        assertThat(tournament.status()).isEqualTo(TournamentStatus.RUNNING);
        assertThatThrownBy(() -> tournament.start(DEADLINE.plusSeconds(1), 2))
            .isInstanceOf(IllegalStateException.class); // RUNNING не перезапускается
    }

    @Test
    @DisplayName("cancel: DRAFT и REGISTRATION_OPEN → CANCELLED; RUNNING/FINISHED запрещены")
    void cancel_переходы() {
        draft().cancel(NOW); // из DRAFT можно

        Tournament open = draft();
        open.openRegistration(NOW);
        open.cancel(NOW.plusSeconds(1));
        assertThat(open.status()).isEqualTo(TournamentStatus.CANCELLED);
        assertThatThrownBy(() -> open.cancel(NOW.plusSeconds(2)))
            .isInstanceOf(IllegalStateException.class); // уже отменён

        Tournament running = started();
        assertThatThrownBy(() -> running.cancel(DEADLINE.plusSeconds(1)))
            .isInstanceOf(IllegalStateException.class); // активный не отменяется (раздел 7)
    }

    @Test
    @DisplayName("cancelForInsufficientParticipants: REGISTRATION_OPEN → CANCELLED с причиной")
    void cancel_insufficient() {
        Tournament tournament = draft();
        assertThatThrownBy(() -> tournament.cancelForInsufficientParticipants(NOW))
            .isInstanceOf(IllegalStateException.class); // ещё DRAFT

        tournament.openRegistration(NOW);
        tournament.cancelForInsufficientParticipants(DEADLINE);
        assertThat(tournament.status()).isEqualTo(TournamentStatus.CANCELLED);
        assertThat(tournament.cancelReason()).isEqualTo(CancelReason.INSUFFICIENT_PARTICIPANTS);
    }

    @Test
    @DisplayName("updateParameters/replaceTags: только DRAFT; описание — безопасное и после открытия")
    void изменения_параметров() {
        Tournament tournament = draft();
        tournament.updateParameters("Новое имя", DEADLINE.plusSeconds(60),
            Duration.ofHours(2), 0.75, 4, NOW);
        assertThat(tournament.name()).isEqualTo("Новое имя");
        assertThat(tournament.minParticipants()).isEqualTo(4);
        tournament.replaceTags(Set.of(UUID.randomUUID()));
        assertThat(tournament.tagIds()).hasSize(1);

        Tournament open = draft();
        open.openRegistration(NOW);
        assertThatThrownBy(() -> open.updateParameters("Н", DEADLINE,
            Duration.ofHours(1), 0.5, 2, NOW))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> open.replaceTags(Set.of()))
            .isInstanceOf(IllegalStateException.class);

        open.updateDescription("Безопасное описание"); // разрешено после открытия
        assertThat(open.description()).isEqualTo("Безопасное описание");
        Tournament running = started();
        running.updateDescription("Описание бегущего"); // и в RUNNING
        assertThatThrownBy(() -> running.updateParameters("Н", DEADLINE,
            Duration.ofHours(1), 0.5, 2, DEADLINE))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("canInvite/isAcceptingNow: дедлайн и статус решают")
    void окна_приглашений() {
        Tournament tournament = draft();
        assertThat(tournament.canInvite(NOW)).isTrue();   // DRAFT, до дедлайна
        assertThat(tournament.isAcceptingNow(NOW)).isFalse(); // приём закрыт

        tournament.openRegistration(NOW);
        assertThat(tournament.isAcceptingNow(NOW)).isTrue();
        assertThat(tournament.isAcceptingNow(DEADLINE)).isFalse(); // [.., deadline)

        tournament.start(DEADLINE, 2);
        assertThat(tournament.canInvite(DEADLINE.plusSeconds(1))).isFalse();
        assertThat(tournament.isAcceptingNow(DEADLINE.plusSeconds(1))).isFalse();
    }

    private Tournament started() {
        Tournament tournament = draft();
        tournament.openRegistration(NOW);
        tournament.start(DEADLINE, 2);
        return tournament;
    }
}
```

- [ ] **Step 2: Запустить — красный**

```bash
./mvnw -q test -Dtest=TournamentTest
```

Ожидание: FAIL (компиляция): `cannot find symbol: class Tournament`.

- [ ] **Step 3: Реализация Tournament**

`src/main/java/com/plantarena/tournaments/domain/TournamentStatus.java`:

```java
package com.plantarena.tournaments.domain;

/**
 * State machine закрытого турнира (раздел 7): DRAFT → REGISTRATION_OPEN →
 * RUNNING → FINISHED; DRAFT/REGISTRATION_OPEN → CANCELLED. FINISHED —
 * итерация 6 (выбывание до победителя).
 */
public enum TournamentStatus {
    DRAFT, REGISTRATION_OPEN, RUNNING, FINISHED, CANCELLED
}
```

`src/main/java/com/plantarena/tournaments/domain/TournamentType.java`:

```java
package com.plantarena.tournaments.domain;

/** Тип турнира: PRIVATE — закрытый (глобальный GLOBAL — итерация 7). */
public enum TournamentType {
    PRIVATE
}
```

`src/main/java/com/plantarena/tournaments/domain/EliminationAlgorithmKind.java`:

```java
package com.plantarena.tournaments.domain;

/** Алгоритм выбывания (раздел 7): в лабораторной один — ROUND_ELIMINATION. */
public enum EliminationAlgorithmKind {
    ROUND_ELIMINATION
}
```

`src/main/java/com/plantarena/tournaments/domain/CancelReason.java`:

```java
package com.plantarena.tournaments.domain;

/** Причина автоматической отмены турнира (раздел 7). */
public enum CancelReason {
    INSUFFICIENT_PARTICIPANTS
}
```

`src/main/java/com/plantarena/tournaments/domain/Tournament.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Агрегат tournaments (раздел 7): закрытый турнир. Инварианты: параметры
 * валидны (дедлайн в будущем, 0 &lt; доля &lt; 1, minParticipants ≥ 2,
 * длительность положительна); параметры и теги меняются только в DRAFT;
 * описание — безопасное изменение в DRAFT/REGISTRATION_OPEN/RUNNING; старт —
 * только из REGISTRATION_OPEN после дедлайна при READY ≥ minParticipants;
 * отмена — только до RUNNING; отмена активного турнира запрещена. Время
 * приходит аргументом.
 */
public final class Tournament {

    private final UUID id;
    private final UUID creatorId;
    private String name;
    private String description;
    private final TournamentType type;
    private TournamentStatus status;
    private final EliminationAlgorithmKind algorithm;
    private Instant registrationDeadline;
    private Duration roundDuration;
    private double eliminationFraction;
    private int minParticipants;
    private CancelReason cancelReason;
    private final Set<UUID> tagIds = new HashSet<>();
    private final Instant createdAt;
    private long version;

    private Tournament(UUID id, UUID creatorId, String name, String description,
                       TournamentType type, TournamentStatus status,
                       EliminationAlgorithmKind algorithm, Instant registrationDeadline,
                       Duration roundDuration, double eliminationFraction, int minParticipants,
                       CancelReason cancelReason, Set<UUID> tagIds, Instant createdAt,
                       long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.creatorId = Objects.requireNonNull(creatorId, "creatorId");
        this.type = Objects.requireNonNull(type, "type");
        this.status = Objects.requireNonNull(status, "status");
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        this.cancelReason = cancelReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
        applyParameters(name, description, registrationDeadline, roundDuration,
            eliminationFraction, minParticipants, createdAt);
        this.tagIds.addAll(tagIds == null ? Set.of() : tagIds);
    }

    /** Новый черновик закрытого турнира (создаёт модератор/админ, раздел 13). */
    public static Tournament createDraft(UUID creatorId, String name, String description,
                                         Instant registrationDeadline, Duration roundDuration,
                                         double eliminationFraction, int minParticipants,
                                         Set<UUID> tagIds, Instant now) {
        return new Tournament(UUID.randomUUID(), creatorId, name, description,
            TournamentType.PRIVATE, TournamentStatus.DRAFT,
            EliminationAlgorithmKind.ROUND_ELIMINATION, registrationDeadline, roundDuration,
            eliminationFraction, minParticipants, null, tagIds, now, 0);
    }

    /** Восстановление из хранилища (использует только persistence-адаптер). */
    public static Tournament restore(UUID id, UUID creatorId, String name, String description,
                                     TournamentType type, TournamentStatus status,
                                     EliminationAlgorithmKind algorithm,
                                     Instant registrationDeadline, Duration roundDuration,
                                     double eliminationFraction, int minParticipants,
                                     CancelReason cancelReason, Set<UUID> tagIds,
                                     Instant createdAt, long version) {
        return new Tournament(id, creatorId, name, description, type, status, algorithm,
            registrationDeadline, roundDuration, eliminationFraction, minParticipants,
            cancelReason, tagIds, createdAt, version);
    }

    /** Открыть приём заявок: только из DRAFT и до дедлайна. */
    public void openRegistration(Instant now) {
        requireStatus(TournamentStatus.DRAFT);
        if (!now.isBefore(registrationDeadline)) {
            throw new IllegalStateException("Нельзя открыть регистрацию после дедлайна");
        }
        status = TournamentStatus.REGISTRATION_OPEN;
    }

    /** Старт: после дедлайна при достаточном числе READY-заявок (раздел 7). */
    public void start(Instant now, int readyCount) {
        requireStatus(TournamentStatus.REGISTRATION_OPEN);
        if (now.isBefore(registrationDeadline)) {
            throw new IllegalStateException("Старт возможен только после дедлайна");
        }
        if (readyCount < minParticipants) {
            throw new IllegalStateException(
                "Недостаточно READY-заявок: " + readyCount + " < " + minParticipants);
        }
        status = TournamentStatus.RUNNING;
    }

    /** Явная отмена организатором: только до RUNNING (раздел 7). */
    public void cancel(Instant now) {
        requireStatus(TournamentStatus.DRAFT, TournamentStatus.REGISTRATION_OPEN);
        status = TournamentStatus.CANCELLED;
    }

    /** Автоматическая отмена по дедлайну при нехватке участников. */
    public void cancelForInsufficientParticipants(Instant now) {
        requireStatus(TournamentStatus.REGISTRATION_OPEN);
        status = TournamentStatus.CANCELLED;
        cancelReason = CancelReason.INSUFFICIENT_PARTICIPANTS;
    }

    /** Изменение параметров: только в DRAFT (раздел 13). */
    public void updateParameters(String name, Instant registrationDeadline,
                                 Duration roundDuration, double eliminationFraction,
                                 int minParticipants, Instant now) {
        requireStatus(TournamentStatus.DRAFT);
        applyParameters(name, description, registrationDeadline, roundDuration,
            eliminationFraction, minParticipants, now);
    }

    /** Безопасное изменение описания: DRAFT/REGISTRATION_OPEN/RUNNING. */
    public void updateDescription(String description) {
        requireStatus(TournamentStatus.DRAFT, TournamentStatus.REGISTRATION_OPEN,
            TournamentStatus.RUNNING);
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Описание не может быть пустым");
        }
        this.description = description.trim();
    }

    /** Изменение тегов: только в DRAFT (tagIds — параметры турнира). */
    public void replaceTags(Set<UUID> tagIds) {
        requireStatus(TournamentStatus.DRAFT);
        this.tagIds.clear();
        this.tagIds.addAll(tagIds == null ? Set.of() : tagIds);
    }

    /** Приглашать можно в DRAFT/REGISTRATION_OPEN до дедлайна (раздел 7). */
    public boolean canInvite(Instant now) {
        return (status == TournamentStatus.DRAFT || status == TournamentStatus.REGISTRATION_OPEN)
            && now.isBefore(registrationDeadline);
    }

    /** Принимать приглашения можно только в REGISTRATION_OPEN до дедлайна. */
    public boolean isAcceptingNow(Instant now) {
        return status == TournamentStatus.REGISTRATION_OPEN
            && now.isBefore(registrationDeadline);
    }

    private void applyParameters(String name, String description,
                                 Instant registrationDeadline, Duration roundDuration,
                                 double eliminationFraction, int minParticipants, Instant now) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Название турнира обязательно");
        }
        if (registrationDeadline == null || !registrationDeadline.isAfter(now)) {
            throw new IllegalArgumentException("Дедлайн регистрации должен быть в будущем");
        }
        if (roundDuration == null || roundDuration.isZero() || roundDuration.isNegative()) {
            throw new IllegalArgumentException("Длительность раунда должна быть положительной");
        }
        if (eliminationFraction <= 0d || eliminationFraction >= 1d) {
            throw new IllegalArgumentException("Доля выбывания должна быть в (0, 1)");
        }
        if (minParticipants < 2) {
            throw new IllegalArgumentException("minParticipants не меньше 2 (раздел 7)");
        }
        this.name = name.trim();
        this.description = description == null ? null : description.trim();
        this.registrationDeadline = registrationDeadline;
        this.roundDuration = roundDuration;
        this.eliminationFraction = eliminationFraction;
        this.minParticipants = minParticipants;
    }

    private void requireStatus(TournamentStatus... allowed) {
        for (TournamentStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("Недопустимый статус для операции: " + status);
    }

    public UUID id() {
        return id;
    }

    public UUID creatorId() {
        return creatorId;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public TournamentType type() {
        return type;
    }

    public TournamentStatus status() {
        return status;
    }

    public EliminationAlgorithmKind algorithm() {
        return algorithm;
    }

    public Instant registrationDeadline() {
        return registrationDeadline;
    }

    public Duration roundDuration() {
        return roundDuration;
    }

    public double eliminationFraction() {
        return eliminationFraction;
    }

    public int minParticipants() {
        return minParticipants;
    }

    public CancelReason cancelReason() {
        return cancelReason;
    }

    public Set<UUID> tagIds() {
        return Collections.unmodifiableSet(tagIds);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }
}
```

- [ ] **Step 4: Запустить — зелёный**

```bash
./mvnw -q test -Dtest=TournamentTest
```

Ожидание: PASS (8 тестов).

- [ ] **Step 5: Красный тест Tag + порты репозиториев**

`src/test/java/com/plantarena/tournaments/domain/TagTest.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Агрегат Tag: справочник тематик, валидация имени")
class TagTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Test
    @DisplayName("фабрика: имя обрезается, пустое запрещено")
    void фабрика_имя() {
        Tag tag = Tag.create("  Комнатные  ", NOW);
        assertThat(tag.name()).isEqualTo("Комнатные");
        assertThat(tag.createdAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> Tag.create("  ", NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tag.create(null, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rename: то же правило")
    void rename() {
        Tag tag = Tag.create("Комнатные", NOW);
        tag.rename("Кактусы");
        assertThat(tag.name()).isEqualTo("Кактусы");
        assertThatThrownBy(() -> tag.rename(" "))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 6: Запустить — красный**

```bash
./mvnw -q test -Dtest=TagTest
```

Ожидание: FAIL (компиляция): `cannot find symbol: class Tag`.

- [ ] **Step 7: Реализация Tag и порты репозиториев**

`src/main/java/com/plantarena/tournaments/domain/Tag.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат tournaments: тег справочника тематик (раздел 13). Простой
 * справочник — без version (единственная мутация rename, админ).
 */
public final class Tag {

    private final UUID id;
    private String name;
    private final Instant createdAt;

    private Tag(UUID id, String name, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = requireName(name);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static Tag create(String name, Instant now) {
        return new Tag(UUID.randomUUID(), name, now);
    }

    public static Tag restore(UUID id, String name, Instant createdAt) {
        return new Tag(id, name, createdAt);
    }

    public void rename(String name) {
        this.name = requireName(name);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Название тега обязательно");
        }
        return name.trim();
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
```

`src/main/java/com/plantarena/tournaments/domain/TournamentRepository.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Порт репозитория агрегата Tournament. search/count — список доступных
 * пользователю турниров (организатор/активное приглашение/участие; админ —
 * все) с фильтрами статуса и тега (раздел 13); findDueForStart —
 * REGISTRATION_OPEN с наступившим дедлайном (scheduler, раздел 7).
 */
public interface TournamentRepository {

    Tournament save(Tournament tournament);

    Optional<Tournament> findById(UUID id);

    /** Удаление пустого черновика (проверка «пустой» — в application). */
    void delete(UUID id);

    List<Tournament> search(TournamentFilter filter);

    long count(TournamentFilter filter);

    /** Турниры, готовые к старту/отмене по дедлайну: deadline <= now. */
    List<Tournament> findDueForStart(Instant now, int limit);

    /**
     * @param userId пользователь (null для админа — видеть все)
     * @param admin  признак роли ADMIN
     * @param status фильтр по статусу (null — любой)
     * @param tagId  фильтр по тегу (null — любой)
     */
    record TournamentFilter(UUID userId, boolean admin, TournamentStatus status, UUID tagId,
                            int offset, int size) {
    }
}
```

`src/main/java/com/plantarena/tournaments/domain/TagRepository.java`:

```java
package com.plantarena.tournaments.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Порт репозитория справочника тегов; isUsedByTournament — для удаления (409). */
public interface TagRepository {

    Tag save(Tag tag);

    Optional<Tag> findById(UUID id);

    Optional<Tag> findByName(String name);

    List<Tag> findAll(int offset, int size);

    long count();

    void delete(UUID id);

    /** Используется ли тег хотя бы одним турниром (M2M tournament_tag). */
    boolean isUsedByTournament(UUID tagId);
}
```

- [ ] **Step 8: Запустить — зелёный и Commit**

```bash
./mvnw -q test -Dtest='TournamentTest,TagTest'
```

Ожидание: PASS (10 тестов).

```bash
git add src/main/java/com/plantarena/tournaments/domain src/test/java/com/plantarena/tournaments/domain
git commit -m "feat(tournaments): домен — агрегаты Tournament (state machine) и Tag, порты репозиториев"
```

---

### Task 3: Домен Invitation и TournamentEntry — переходы заявок

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/domain/InvitationStatus.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/Invitation.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/InvitationRepository.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/EntryStatus.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/TournamentEntry.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/TournamentEntryRepository.java`
- Test: `src/test/java/com/plantarena/tournaments/domain/InvitationTest.java`
- Test: `src/test/java/com/plantarena/tournaments/domain/TournamentEntryTest.java`

**Interfaces:**
- Consumes: ничего (чистая Java).
- Produces (для Tasks 5–7): `Invitation.invite(tournamentId, userId, invitedBy, now)`, `restore(id, tournamentId, userId, invitedBy, status, invitedAt, respondedAt, submittedPlantId, reservationId, submissionKey, version)`, `accept(plantId, reservationId, submissionKey, plantApproved, now)`, `markReady(now)`, `rollbackToInvited(now)`, `decline(now)`, `revoke(now)`, `expire(now)`, геттеры `id()/tournamentId()/userId()/invitedBy()/status()/invitedAt()/respondedAt()/submittedPlantId()/reservationId()/submissionKey()/version()`; enum `InvitationStatus {INVITED, ACCEPTED_PENDING_MODERATION, READY, DECLINED, REVOKED, EXPIRED}`; `TournamentEntry.admit(tournamentId, userId, plantId, reservationId, now)`, `restore(id, tournamentId, userId, plantId, reservationId, status, joinedAt)`, геттеры; enum `EntryStatus {ACTIVE, ELIMINATED, WINNER}`; порты `InvitationRepository {save, findById, findByTournamentIdAndUserId, findBySubmittedPlantIdAndStatus, findByTournamentId, countByTournamentId, findByUserId, countByUserId, findByTournamentIdAndStatus, existsByTournamentId}` и `TournamentEntryRepository {save, findByTournamentId, countByTournamentId, existsByTournamentIdAndUserId}`.

- [ ] **Step 1: Красный доменный тест Invitation**

`src/test/java/com/plantarena/tournaments/domain/InvitationTest.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Агрегат Invitation: переходы заявок раздела 7, история последней подачи")
class InvitationTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORGANIZER_ID = UUID.randomUUID();

    @Test
    @DisplayName("фабрика: INVITED, invitedAt=now, без заявки")
    void фабрика_invited() {
        Invitation invitation = Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW);

        assertThat(invitation.status()).isEqualTo(InvitationStatus.INVITED);
        assertThat(invitation.tournamentId()).isEqualTo(TOURNAMENT_ID);
        assertThat(invitation.userId()).isEqualTo(USER_ID);
        assertThat(invitation.invitedBy()).isEqualTo(ORGANIZER_ID);
        assertThat(invitation.invitedAt()).isEqualTo(NOW);
        assertThat(invitation.respondedAt()).isNull();
        assertThat(invitation.submittedPlantId()).isNull();
        assertThat(invitation.reservationId()).isNull();
    }

    @Test
    @DisplayName("accept: PENDING-растение → ACCEPTED_PENDING_MODERATION; APPROVED → сразу READY")
    void accept_два_пути() {
        UUID plantId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        Invitation pending = Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW);
        pending.accept(plantId, reservationId, key, false, NOW.plusSeconds(1));
        assertThat(pending.status()).isEqualTo(InvitationStatus.ACCEPTED_PENDING_MODERATION);
        assertThat(pending.submittedPlantId()).isEqualTo(plantId);
        assertThat(pending.reservationId()).isEqualTo(reservationId);
        assertThat(pending.submissionKey()).isEqualTo(key);
        assertThat(pending.respondedAt()).isEqualTo(NOW.plusSeconds(1));

        Invitation ready = Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW);
        ready.accept(plantId, reservationId, key, true, NOW.plusSeconds(1));
        assertThat(ready.status()).isEqualTo(InvitationStatus.READY);
    }

    @Test
    @DisplayName("accept только из INVITED; закрытое приглашение не принимается")
    void accept_только_из_invited() {
        Invitation accepted = accepted(false);
        assertThatThrownBy(() -> accepted.accept(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), false, NOW))
            .isInstanceOf(IllegalStateException.class);

        Invitation declined = Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW);
        declined.decline(NOW);
        assertThatThrownBy(() -> declined.accept(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), false, NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markReady: ACCEPTED_PENDING_MODERATION → READY (решение модерации)")
    void mark_ready() {
        Invitation invitation = accepted(false);
        invitation.markReady(NOW.plusSeconds(5));
        assertThat(invitation.status()).isEqualTo(InvitationStatus.READY);
        assertThat(invitation.respondedAt()).isEqualTo(NOW.plusSeconds(5));

        assertThatThrownBy(() -> Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW)
            .markReady(NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("rollbackToInvited: REJECTED → INVITED, история подачи сохранена, резерв сброшен")
    void rollback_после_rejected() {
        Invitation invitation = accepted(false);
        UUID plantId = invitation.submittedPlantId();

        invitation.rollbackToInvited(NOW.plusSeconds(5));

        assertThat(invitation.status()).isEqualTo(InvitationStatus.INVITED);
        assertThat(invitation.submittedPlantId()).isEqualTo(plantId); // история последней подачи
        assertThat(invitation.reservationId()).isNull();
        assertThat(invitation.submissionKey()).isNull(); // новая подача — новый ключ
    }

    @Test
    @DisplayName("decline: из INVITED и ACCEPTED_PENDING_MODERATION; повтор — ошибка")
    void decline_переходы() {
        Invitation fromInvited = Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW);
        fromInvited.decline(NOW);
        assertThat(fromInvited.status()).isEqualTo(InvitationStatus.DECLINED);

        Invitation fromAccepted = accepted(false);
        fromAccepted.decline(NOW);
        assertThat(fromAccepted.status()).isEqualTo(InvitationStatus.DECLINED);

        assertThatThrownBy(() -> fromAccepted.decline(NOW.plusSeconds(1)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("revoke: только не принятые (INVITED); принятое — конфликт организатора")
    void revoke_только_invited() {
        Invitation invitation = Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW);
        invitation.revoke(NOW);
        assertThat(invitation.status()).isEqualTo(InvitationStatus.REVOKED);

        assertThatThrownBy(() -> accepted(false).revoke(NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("expire: INVITED и ACCEPTED_PENDING_MODERATION → EXPIRED (старт по дедлайну)")
    void expire_переходы() {
        Invitation invited = Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW);
        invited.expire(NOW.plusSeconds(10));
        assertThat(invited.status()).isEqualTo(InvitationStatus.EXPIRED);

        Invitation accepted = accepted(false);
        accepted.expire(NOW.plusSeconds(10));
        assertThat(accepted.status()).isEqualTo(InvitationStatus.EXPIRED);
        assertThat(accepted.reservationId()).isNull();

        assertThatThrownBy(() -> accepted(true).expire(NOW.plusSeconds(10)))
            .isInstanceOf(IllegalStateException.class); // READY не истекает
    }

    private Invitation accepted(boolean approved) {
        Invitation invitation = Invitation.invite(TOURNAMENT_ID, USER_ID, ORGANIZER_ID, NOW);
        invitation.accept(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            approved, NOW.plusSeconds(1));
        return invitation;
    }
}
```

- [ ] **Step 2: Запустить — красный**

```bash
./mvnw -q test -Dtest=InvitationTest
```

Ожидание: FAIL (компиляция): `cannot find symbol: class Invitation`.

- [ ] **Step 3: Реализация Invitation**

`src/main/java/com/plantarena/tournaments/domain/InvitationStatus.java`:

```java
package com.plantarena.tournaments.domain;

/**
 * Переходы заявки (раздел 7): INVITED → ACCEPTED_PENDING_MODERATION → READY;
 * INVITED → DECLINED/REVOKED/EXPIRED; ACCEPTED_PENDING_MODERATION →
 * INVITED (REJECTED, повторная подача) / DECLINED / EXPIRED. REVOKED/EXPIRED
 * и DECLINED терминальны; поздний результат модерации их не меняет.
 */
public enum InvitationStatus {
    INVITED, ACCEPTED_PENDING_MODERATION, READY, DECLINED, REVOKED, EXPIRED
}
```

`src/main/java/com/plantarena/tournaments/domain/Invitation.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат tournaments (раздел 7): приглашение пользователя в закрытый
 * турнир и его заявка. Инварианты: переходы только по разделу 7;
 * submittedPlantId сохраняется при возврате в INVITED (история последней
 * подачи); reservationId/submissionKey живут только у принятой заявки
 * (освобождение резерва — команда application-слоя в той же tx, ADR-010).
 * Уникальность (tournamentId, userId) — БД. Время приходит аргументом.
 */
public final class Invitation {

    private final UUID id;
    private final UUID tournamentId;
    private final UUID userId;
    private final UUID invitedBy;
    private InvitationStatus status;
    private final Instant invitedAt;
    private Instant respondedAt;
    private UUID submittedPlantId;
    private UUID reservationId;
    private UUID submissionKey;
    private long version;

    private Invitation(UUID id, UUID tournamentId, UUID userId, UUID invitedBy,
                       InvitationStatus status, Instant invitedAt, Instant respondedAt,
                       UUID submittedPlantId, UUID reservationId, UUID submissionKey,
                       long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tournamentId = Objects.requireNonNull(tournamentId, "tournamentId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.invitedBy = Objects.requireNonNull(invitedBy, "invitedBy");
        this.status = Objects.requireNonNull(status, "status");
        this.invitedAt = Objects.requireNonNull(invitedAt, "invitedAt");
        this.submittedPlantId = submittedPlantId;
        this.reservationId = reservationId;
        this.submissionKey = submissionKey;
        this.respondedAt = respondedAt;
        this.version = version;
    }

    /** Новое приглашение (организатор, до дедлайна — проверяет application). */
    public static Invitation invite(UUID tournamentId, UUID userId, UUID invitedBy, Instant now) {
        return new Invitation(UUID.randomUUID(), tournamentId, userId, invitedBy,
            InvitationStatus.INVITED, now, null, null, null, null, 0);
    }

    /** Восстановление из хранилища (использует только persistence-адаптер). */
    public static Invitation restore(UUID id, UUID tournamentId, UUID userId, UUID invitedBy,
                                     InvitationStatus status, Instant invitedAt,
                                     Instant respondedAt, UUID submittedPlantId,
                                     UUID reservationId, UUID submissionKey, long version) {
        return new Invitation(id, tournamentId, userId, invitedBy, status, invitedAt,
            respondedAt, submittedPlantId, reservationId, submissionKey, version);
    }

    /**
     * Принять приглашение с растением: APPROVED-растение → сразу READY,
     * идущая модерация → ACCEPTED_PENDING_MODERATION (не допуск к голосованию).
     */
    public void accept(UUID plantId, UUID reservationId, UUID submissionKey,
                       boolean plantApproved, Instant now) {
        requireStatus(InvitationStatus.INVITED);
        this.submittedPlantId = Objects.requireNonNull(plantId, "plantId");
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
        this.submissionKey = Objects.requireNonNull(submissionKey, "submissionKey");
        this.status = plantApproved
            ? InvitationStatus.READY
            : InvitationStatus.ACCEPTED_PENDING_MODERATION;
        this.respondedAt = now;
    }

    /** Модерация одобрила: ACCEPTED_PENDING_MODERATION → READY (дедлайн — в application). */
    public void markReady(Instant now) {
        requireStatus(InvitationStatus.ACCEPTED_PENDING_MODERATION);
        status = InvitationStatus.READY;
        respondedAt = now;
    }

    /** Модерация отклонила: возврат в INVITED, история подачи сохранена (раздел 7). */
    public void rollbackToInvited(Instant now) {
        requireStatus(InvitationStatus.ACCEPTED_PENDING_MODERATION);
        status = InvitationStatus.INVITED;
        reservationId = null;
        submissionKey = null;
        respondedAt = now;
    }

    /** Отказ адресата до старта (резерв освобождает application). */
    public void decline(Instant now) {
        requireStatus(InvitationStatus.INVITED, InvitationStatus.ACCEPTED_PENDING_MODERATION);
        status = InvitationStatus.DECLINED;
        reservationId = null;
        submissionKey = null;
        respondedAt = now;
    }

    /** Отзыв организатором: только не принятое приглашение (раздел 7). */
    public void revoke(Instant now) {
        requireStatus(InvitationStatus.INVITED);
        status = InvitationStatus.REVOKED;
        respondedAt = now;
    }

    /** Дедлайн прошёл, заявка не READY (резерв освобождает application). */
    public void expire(Instant now) {
        requireStatus(InvitationStatus.INVITED, InvitationStatus.ACCEPTED_PENDING_MODERATION);
        status = InvitationStatus.EXPIRED;
        reservationId = null;
        submissionKey = null;
        respondedAt = now;
    }

    private void requireStatus(InvitationStatus... allowed) {
        for (InvitationStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("Недопустимый статус для операции: " + status);
    }

    public UUID id() {
        return id;
    }

    public UUID tournamentId() {
        return tournamentId;
    }

    public UUID userId() {
        return userId;
    }

    public UUID invitedBy() {
        return invitedBy;
    }

    public InvitationStatus status() {
        return status;
    }

    public Instant invitedAt() {
        return invitedAt;
    }

    public Instant respondedAt() {
        return respondedAt;
    }

    public UUID submittedPlantId() {
        return submittedPlantId;
    }

    public UUID reservationId() {
        return reservationId;
    }

    public UUID submissionKey() {
        return submissionKey;
    }

    public long version() {
        return version;
    }
}
```

- [ ] **Step 4: Запустить — зелёный**

```bash
./mvnw -q test -Dtest=InvitationTest
```

Ожидание: PASS (8 тестов).

- [ ] **Step 5: Красный тест TournamentEntry**

`src/test/java/com/plantarena/tournaments/domain/TournamentEntryTest.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Агрегат TournamentEntry: допуск участника при старте (ACTIVE)")
class TournamentEntryTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Test
    @DisplayName("admit: ACTIVE с зафиксированным растением и резервом")
    void admit_active() {
        UUID tournamentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        TournamentEntry entry = TournamentEntry.admit(tournamentId, userId, plantId,
            reservationId, NOW);

        assertThat(entry.tournamentId()).isEqualTo(tournamentId);
        assertThat(entry.userId()).isEqualTo(userId);
        assertThat(entry.plantId()).isEqualTo(plantId);
        assertThat(entry.reservationId()).isEqualTo(reservationId);
        assertThat(entry.status()).isEqualTo(EntryStatus.ACTIVE);
        assertThat(entry.joinedAt()).isEqualTo(NOW);
    }
}
```

- [ ] **Step 6: Запустить — красный**

```bash
./mvnw -q test -Dtest=TournamentEntryTest
```

Ожидание: FAIL (компиляция): `cannot find symbol: class TournamentEntry`.

- [ ] **Step 7: Реализация TournamentEntry и порты**

`src/main/java/com/plantarena/tournaments/domain/EntryStatus.java`:

```java
package com.plantarena.tournaments.domain;

/**
 * Участие в PRIVATE-турнире (раздел 11): ACTIVE → ELIMINATED/WINNER.
 * Переходы выбывания/победы — итерация 6 (закрытие окон).
 */
public enum EntryStatus {
    ACTIVE, ELIMINATED, WINNER
}
```

`src/main/java/com/plantarena/tournaments/domain/TournamentEntry.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат tournaments (раздел 7): участие пользователя с растением в
 * закрытом турнире. Создаётся при старте из READY-заявки; после старта
 * состав неизменяем. Уникальность (tournamentId, userId) — БД.
 */
public final class TournamentEntry {

    private final UUID id;
    private final UUID tournamentId;
    private final UUID userId;
    private final UUID plantId;
    private final UUID reservationId;
    private final EntryStatus status;
    private final Instant joinedAt;

    private TournamentEntry(UUID id, UUID tournamentId, UUID userId, UUID plantId,
                            UUID reservationId, EntryStatus status, Instant joinedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tournamentId = Objects.requireNonNull(tournamentId, "tournamentId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.plantId = Objects.requireNonNull(plantId, "plantId");
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
        this.status = Objects.requireNonNull(status, "status");
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
    }

    /** Допуск READY-заявки к старту (use case старта, ADR-010). */
    public static TournamentEntry admit(UUID tournamentId, UUID userId, UUID plantId,
                                        UUID reservationId, Instant now) {
        return new TournamentEntry(UUID.randomUUID(), tournamentId, userId, plantId,
            reservationId, EntryStatus.ACTIVE, now);
    }

    /** Восстановление из хранилища (использует только persistence-адаптер). */
    public static TournamentEntry restore(UUID id, UUID tournamentId, UUID userId,
                                          UUID plantId, UUID reservationId,
                                          EntryStatus status, Instant joinedAt) {
        return new TournamentEntry(id, tournamentId, userId, plantId, reservationId,
            status, joinedAt);
    }

    public UUID id() {
        return id;
    }

    public UUID tournamentId() {
        return tournamentId;
    }

    public UUID userId() {
        return userId;
    }

    public UUID plantId() {
        return plantId;
    }

    public UUID reservationId() {
        return reservationId;
    }

    public EntryStatus status() {
        return status;
    }

    public Instant joinedAt() {
        return joinedAt;
    }
}
```

`src/main/java/com/plantarena/tournaments/domain/InvitationRepository.java`:

```java
package com.plantarena.tournaments.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Порт репозитория агрегата Invitation. Уникальность (tournamentId, userId)
 * — БД (UNIQUE); findBySubmittedPlantIdAndStatus — реакция на решение
 * модерации (раздел 4.3).
 */
public interface InvitationRepository {

    Invitation save(Invitation invitation);

    Optional<Invitation> findById(UUID id);

    Optional<Invitation> findByTournamentIdAndUserId(UUID tournamentId, UUID userId);

    /** Заявка с растением в данном статусе (реакция на PlantModerationDecided). */
    Optional<Invitation> findBySubmittedPlantIdAndStatus(UUID plantId, InvitationStatus status);

    List<Invitation> findByTournamentId(UUID tournamentId, int offset, int size);

    long countByTournamentId(UUID tournamentId);

    List<Invitation> findByUserId(UUID userId, int offset, int size);

    long countByUserId(UUID userId);

    List<Invitation> findByTournamentIdAndStatus(UUID tournamentId, InvitationStatus status);

    /** Есть ли хоть одно приглашение (проверка «пустой черновик» при удалении). */
    boolean existsByTournamentId(UUID tournamentId);
}
```

`src/main/java/com/plantarena/tournaments/domain/TournamentEntryRepository.java`:

```java
package com.plantarena.tournaments.domain;

import java.util.List;
import java.util.UUID;

/** Порт репозитория агрегата TournamentEntry (уникальность пары — БД). */
public interface TournamentEntryRepository {

    TournamentEntry save(TournamentEntry entry);

    List<TournamentEntry> findByTournamentId(UUID tournamentId, int offset, int size);

    long countByTournamentId(UUID tournamentId);

    /** Участие пользователя в турнире (право просмотра, раздел 13). */
    boolean existsByTournamentIdAndUserId(UUID tournamentId, UUID userId);
}
```

- [ ] **Step 8: Запустить — зелёный и Commit**

```bash
./mvnw -q test -Dtest='TournamentTest,TagTest,InvitationTest,TournamentEntryTest'
```

Ожидание: PASS (19 тестов).

```bash
git add src/main/java/com/plantarena/tournaments/domain src/test/java/com/plantarena/tournaments/domain
git commit -m "feat(tournaments): домен — Invitation (переходы, история подачи) и TournamentEntry"
```

---

### Task 4: Application — api-DTO, теги, администрация и запросы турниров

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/api/TournamentData.java`
- Create: `src/main/java/com/plantarena/tournaments/api/InvitationData.java`
- Create: `src/main/java/com/plantarena/tournaments/api/EntryData.java`
- Create: `src/main/java/com/plantarena/tournaments/api/TagData.java`
- Create: `src/main/java/com/plantarena/tournaments/application/TournamentAssembler.java`
- Create: `src/main/java/com/plantarena/tournaments/application/TournamentsAccessPolicy.java`
- Create: `src/main/java/com/plantarena/tournaments/application/{TournamentNotFoundException, InvitationNotFoundException, TagNotFoundException, UnknownUserException, InvitedPlantNotFoundException, TournamentStateConflictException, RegistrationClosedException, DuplicateInvitationException, TagAlreadyExistsException, TagInUseException, PlantNotReservableException, ImageAlreadyReservedException}.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/in/{CreateTournamentUseCase, UpdateTournamentUseCase, DeleteTournamentUseCase, OpenRegistrationUseCase, CancelTournamentUseCase, ListTournamentsUseCase, GetTournamentUseCase, ListEntriesUseCase, ListInvitationsUseCase, TagsUseCase}.java`
- Create: `src/main/java/com/plantarena/tournaments/application/{TournamentAdministrationService, TournamentQueryService, TagsService}.java`
- Create (test): `src/test/java/com/plantarena/tournaments/application/support/{InMemoryTournamentRepository, InMemoryInvitationRepository, InMemoryTournamentEntryRepository, InMemoryTagRepository, FakePlantEligibilityGateway}.java`
- Test: `src/test/java/com/plantarena/tournaments/application/TagsServiceTest.java`
- Test: `src/test/java/com/plantarena/tournaments/application/TournamentAdministrationServiceTest.java`
- Test: `src/test/java/com/plantarena/tournaments/application/TournamentQueryServiceTest.java`

**Interfaces:**
- Consumes: домен Task 2–3, `CurrentActor`/`AppRole`/`AccessDeniedException`/`NotIdentifiedException` (shared.security), `PaginationParams` (shared.web), `PlantEligibilityGateway` (Task 1).
- Produces (для Tasks 5–7): `TournamentData(id, creatorId, name, description, type, status, algorithm, registrationDeadline, roundDurationSeconds, eliminationFraction, minParticipants, cancelReason, tagIds, createdAt)`, `InvitationData(id, tournamentId, userId, status, invitedAt, respondedAt, submittedPlantId)`, `EntryData(id, tournamentId, userId, plantId, status, joinedAt)`, `TagData(id, name)`; `TournamentAssembler.toData(Tournament|Invitation|TournamentEntry|Tag)` (package-private); `TournamentsAccessPolicy.{requireIdentified, requireModeratorOrAdmin, requireAdmin, requireOrganizer, requireAddressee, requireTournamentViewer}`; use case-порты (см. код ниже); исключения; in-memory фейки для Tasks 5–6.

- [ ] **Step 1: api-DTO и ассемблер**

`src/main/java/com/plantarena/tournaments/api/TournamentData.java`:

```java
package com.plantarena.tournaments.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Опубликованные данные турнира (раздел 13). reservationId и внутренние
 * детали заявок не раскрываются; маппинг из домена — в application
 * (TournamentAssembler): api не зависит от domain (LayerRules).
 */
public record TournamentData(
        UUID id,
        UUID creatorId,
        String name,
        String description,
        String type,
        String status,
        String algorithm,
        Instant registrationDeadline,
        long roundDurationSeconds,
        double eliminationFraction,
        int minParticipants,
        String cancelReason,
        Set<UUID> tagIds,
        Instant createdAt) {
}
```

`src/main/java/com/plantarena/tournaments/api/InvitationData.java`:

```java
package com.plantarena.tournaments.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованные данные приглашения (раздел 13): reservationId — внутренний
 * идентификатор plants, наружу не выходит.
 */
public record InvitationData(
        UUID id,
        UUID tournamentId,
        UUID userId,
        String status,
        Instant invitedAt,
        Instant respondedAt,
        UUID submittedPlantId) {
}
```

`src/main/java/com/plantarena/tournaments/api/EntryData.java`:

```java
package com.plantarena.tournaments.api;

import java.time.Instant;
import java.util.UUID;

/** Опубликованные данные участия (раздел 13). */
public record EntryData(
        UUID id,
        UUID tournamentId,
        UUID userId,
        UUID plantId,
        String status,
        Instant joinedAt) {
}
```

`src/main/java/com/plantarena/tournaments/api/TagData.java`:

```java
package com.plantarena.tournaments.api;

import java.util.UUID;

/** Опубликованные данные тега (справочник тематик, раздел 13). */
public record TagData(UUID id, String name) {
}
```

`src/main/java/com/plantarena/tournaments/application/TournamentAssembler.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.tournaments.api.EntryData;
import com.plantarena.tournaments.api.InvitationData;
import com.plantarena.tournaments.api.TagData;
import com.plantarena.tournaments.api.TournamentData;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.Tag;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;

/** Домен → api-DTO (api не зависит от domain, LayerRules). */
final class TournamentAssembler {

    private TournamentAssembler() {
    }

    static TournamentData toData(Tournament tournament) {
        return new TournamentData(tournament.id(), tournament.creatorId(), tournament.name(),
            tournament.description(), tournament.type().name(), tournament.status().name(),
            tournament.algorithm().name(), tournament.registrationDeadline(),
            tournament.roundDuration().toSeconds(), tournament.eliminationFraction(),
            tournament.minParticipants(),
            tournament.cancelReason() == null ? null : tournament.cancelReason().name(),
            tournament.tagIds(), tournament.createdAt());
    }

    static InvitationData toData(Invitation invitation) {
        return new InvitationData(invitation.id(), invitation.tournamentId(),
            invitation.userId(), invitation.status().name(), invitation.invitedAt(),
            invitation.respondedAt(), invitation.submittedPlantId());
    }

    static EntryData toData(TournamentEntry entry) {
        return new EntryData(entry.id(), entry.tournamentId(), entry.userId(), entry.plantId(),
            entry.status().name(), entry.joinedAt());
    }

    static TagData toData(Tag tag) {
        return new TagData(tag.id(), tag.name());
    }
}
```

- [ ] **Step 2: Исключения (единый язык, без HTTP)**

Каждый файл — по одному классу в `src/main/java/com/plantarena/tournaments/application/`:

```java
package com.plantarena.tournaments.application;

/** Турнир не найден или скрыт политикой приватности (раздел 13). */
public class TournamentNotFoundException extends RuntimeException {

    public TournamentNotFoundException(String message) {
        super(message);
    }
}
```

```java
package com.plantarena.tournaments.application;

/** Приглашение не найдено или скрыто (не адресат — раздел 13). */
public class InvitationNotFoundException extends RuntimeException {

    public InvitationNotFoundException(String message) {
        super(message);
    }
}
```

```java
package com.plantarena.tournaments.application;

/** Тег справочника не найден. */
public class TagNotFoundException extends RuntimeException {

    public TagNotFoundException(String message) {
        super(message);
    }
}
```

```java
package com.plantarena.tournaments.application;

/** Пользователь приглашения неизвестен identity (раздел 13: 404). */
public class UnknownUserException extends RuntimeException {

    public UnknownUserException(String message) {
        super(message);
    }
}
```

```java
package com.plantarena.tournaments.application;

/** Растение подачи не найдено (раздел 13: 404). */
public class InvitedPlantNotFoundException extends RuntimeException {

    public InvitedPlantNotFoundException(String message) {
        super(message);
    }
}
```

```java
package com.plantarena.tournaments.application;

/** Операция несовместима со статусом турнира/приглашения (раздел 13: 409). */
public class TournamentStateConflictException extends RuntimeException {

    public TournamentStateConflictException(String message) {
        super(message);
    }
}
```

```java
package com.plantarena.tournaments.application;

/** Дедлайн регистрации прошёл: приём/приглашения закрыты (раздел 7, 409). */
public class RegistrationClosedException extends RuntimeException {

    public RegistrationClosedException(String message) {
        super(message);
    }
}
```

```java
package com.plantarena.tournaments.application;

/** Дубль приглашения: пара (турнир, пользователь) уникальна (раздел 7, 409). */
public class DuplicateInvitationException extends RuntimeException {

    public DuplicateInvitationException(String message) {
        super(message);
    }
}
```

```java
package com.plantarena.tournaments.application;

/** Тег с таким именем уже существует (раздел 13: 409). */
public class TagAlreadyExistsException extends RuntimeException {

    public TagAlreadyExistsException(String message) {
        super(message);
    }
}
```

```java
package com.plantarena.tournaments.application;

/** Тег используется турниром — удаление запрещено (раздел 13: 409). */
public class TagInUseException extends RuntimeException {

    public TagInUseException(String message) {
        super(message);
    }
}
```

```java
package com.plantarena.tournaments.application;

import java.time.Instant;

/**
 * Растение не проходит проверки допуска plants (не владелец/погибло/запрет
 * изображения/не APPROVED) — перевод PlantNotEligibleException из ACL
 * (раздел 13: 409; retryAt — для временного запрета изображения).
 */
public class PlantNotReservableException extends RuntimeException {

    private final Instant retryAt;

    public PlantNotReservableException(String message, Instant retryAt) {
        super(message);
        this.retryAt = retryAt;
    }

    public Instant retryAt() {
        return retryAt;
    }
}
```

```java
package com.plantarena.tournaments.application;

/** Изображение уже активно зарезервировано другой заявкой (раздел 6, 409). */
public class ImageAlreadyReservedException extends RuntimeException {

    public ImageAlreadyReservedException(String message) {
        super(message);
    }
}
```

```java
package com.plantarena.tournaments.application;

/**
 * Неизвестное значение фильтра статуса списка турниров (раздел 13: 400).
 * Фильтр приходит строкой опубликованного языка — разбор в application,
 * адаптер in.web не зависит от домена (LayerRules).
 */
public class UnknownStatusFilterException extends RuntimeException {

    public UnknownStatusFilterException(String message) {
        super(message);
    }
}
```

- [ ] **Step 3: AccessPolicy**

`src/main/java/com/plantarena/tournaments/application/TournamentsAccessPolicy.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.Tournament;
import org.springframework.stereotype.Component;

/**
 * AccessPolicy контекста tournaments (раздел 2/13): правила единого языка.
 * Создание турнира/тегов — модератор/админ; удаление тега — админ;
 * управление турниром — организатор (создатель) или админ; чужое приглашение
 * скрыто (404, как растения plants); просмотр турнира — организатор, админ,
 * активное приглашение (INVITED/ACCEPTED_PENDING_MODERATION/READY) или
 * участие.
 */
@Component
public class TournamentsAccessPolicy {

    public void requireIdentified(CurrentActor actor) {
        if (actor == null || actor.isGuest()) {
            throw new NotIdentifiedException(
                "Требуется идентифицированный пользователь (X-Demo-User-Id в dev/test, ADR-005)");
        }
    }

    /** Создание турнира и тегов: модератор или админ (раздел 13). */
    public void requireModeratorOrAdmin(CurrentActor actor) {
        requireIdentified(actor);
        if (!actor.hasRole(AppRole.MODERATOR) && !actor.hasRole(AppRole.ADMIN)) {
            throw new AccessDeniedException("Действие доступно модератору или администратору");
        }
    }

    /** Изменение/удаление тега: админ (раздел 13). */
    public void requireAdmin(CurrentActor actor) {
        requireIdentified(actor);
        if (!actor.hasRole(AppRole.ADMIN)) {
            throw new AccessDeniedException("Действие доступно администратору");
        }
    }

    /** Управление турниром: создатель или админ (раздел 13). */
    public void requireOrganizer(CurrentActor actor, Tournament tournament) {
        requireIdentified(actor);
        if (actor.userId().equals(tournament.creatorId()) || actor.hasRole(AppRole.ADMIN)) {
            return;
        }
        throw new AccessDeniedException("Действие доступно организатору турнира");
    }

    /** Действие с приглашением: только адресат; чужое скрыто (404). */
    public void requireAddressee(CurrentActor actor, Invitation invitation) {
        requireIdentified(actor);
        if (actor.userId().equals(invitation.userId())) {
            return;
        }
        throw new InvitationNotFoundException(
            "Приглашение не найдено: " + invitation.id());
    }

    /**
     * Просмотр турнира: организатор, админ, активное приглашение или участие.
     * Иначе скрыто (404, раздел 13).
     *
     * @param visibleBeyondOrganizer есть ли у actor активное приглашение/участие
     */
    public void requireTournamentViewer(CurrentActor actor, Tournament tournament,
                                        boolean visibleBeyondOrganizer) {
        requireIdentified(actor);
        if (actor.userId().equals(tournament.creatorId()) || actor.hasRole(AppRole.ADMIN)
                || visibleBeyondOrganizer) {
            return;
        }
        throw new TournamentNotFoundException("Турнир не найден: " + tournament.id());
    }
}
```

- [ ] **Step 4: Порты in (команды и запросы администрации/тегов)**

`src/main/java/com/plantarena/tournaments/application/port/in/CreateTournamentUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Создать PRIVATE DRAFT (раздел 7/13; модератор/админ). */
public interface CreateTournamentUseCase {

    TournamentData create(CurrentActor actor, CreateTournamentCommand command);

    record CreateTournamentCommand(
            String name,
            String description,
            Instant registrationDeadline,
            Duration roundDuration,
            double eliminationFraction,
            int minParticipants,
            Set<UUID> tagIds) {
    }
}
```

`src/main/java/com/plantarena/tournaments/application/port/in/UpdateTournamentUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Изменить турнир (организатор/админ): параметры — только в DRAFT, описание —
 * безопасное изменение и после открытия (раздел 13). null = не менять.
 */
public interface UpdateTournamentUseCase {

    TournamentData update(CurrentActor actor, UUID tournamentId, UpdateTournamentCommand command);

    record UpdateTournamentCommand(
            String name,
            String description,
            Instant registrationDeadline,
            Duration roundDuration,
            Double eliminationFraction,
            Integer minParticipants,
            Set<UUID> tagIds) {
    }
}
```

`src/main/java/com/plantarena/tournaments/application/port/in/DeleteTournamentUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/** Удалить пустой DRAFT (без приглашений), иначе 409 (раздел 13). */
public interface DeleteTournamentUseCase {

    void delete(CurrentActor actor, UUID tournamentId);
}
```

`src/main/java/com/plantarena/tournaments/application/port/in/OpenRegistrationUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.util.UUID;

/** Открыть приём заявок: DRAFT → REGISTRATION_OPEN (раздел 7). */
public interface OpenRegistrationUseCase {

    TournamentData openRegistration(CurrentActor actor, UUID tournamentId);
}
```

`src/main/java/com/plantarena/tournaments/application/port/in/CancelTournamentUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.util.UUID;

/** Отменить турнир до RUNNING с освобождением резервов (раздел 7). */
public interface CancelTournamentUseCase {

    TournamentData cancel(CurrentActor actor, UUID tournamentId);
}
```

`src/main/java/com/plantarena/tournaments/application/port/in/ListTournamentsUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.util.List;
import java.util.UUID;

/**
 * Список доступных пользователю турниров с фильтрами (раздел 13). Статус —
 * строка опубликованного языка (как TournamentData.status): адаптер in.web
 * не зависит от домена (LayerRules), разбор значения — в application.
 */
public interface ListTournamentsUseCase {

    /**
     * @param status фильтр статуса (DRAFT/REGISTRATION_OPEN/RUNNING/
     *               FINISHED/CANCELLED) или null — все статусы
     * @throws com.plantarena.tournaments.application.UnknownStatusFilterException
     *         неизвестное значение фильтра (раздел 13: 400)
     */
    TournamentListResult list(CurrentActor actor, String status, UUID tagId,
                              int page, int size);

    record TournamentListResult(List<TournamentData> items, long total) {
    }
}
```

`src/main/java/com/plantarena/tournaments/application/port/in/GetTournamentUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.util.UUID;

/** Просмотр турнира: организатор/админ/приглашённый/участник (раздел 13). */
public interface GetTournamentUseCase {

    TournamentData get(CurrentActor actor, UUID tournamentId);
}
```

`src/main/java/com/plantarena/tournaments/application/port/in/ListEntriesUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.EntryData;
import java.util.List;
import java.util.UUID;

/** Участники/результаты турнира (раздел 13). */
public interface ListEntriesUseCase {

    EntryListResult list(CurrentActor actor, UUID tournamentId, int page, int size);

    record EntryListResult(List<EntryData> items, long total) {
    }
}
```

`src/main/java/com/plantarena/tournaments/application/port/in/ListInvitationsUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.InvitationData;
import java.util.List;
import java.util.UUID;

/** Списки приглашений: организатору — турнира, пользователю — свои (раздел 13). */
public interface ListInvitationsUseCase {

    InvitationListResult list(CurrentActor actor, UUID tournamentId, int page, int size);

    InvitationListResult listMine(CurrentActor actor, int page, int size);

    record InvitationListResult(List<InvitationData> items, long total) {
    }
}
```

`src/main/java/com/plantarena/tournaments/application/port/in/TagsUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TagData;
import java.util.List;
import java.util.UUID;

/**
 * Справочник тегов (раздел 13): создание — M/A, изменение — A, удаление — A
 * (используемый тег не удаляется), чтение — все идентифицированные.
 */
public interface TagsUseCase {

    TagData create(CurrentActor actor, String name);

    TagData rename(CurrentActor actor, UUID tagId, String name);

    void delete(CurrentActor actor, UUID tagId);

    TagData get(CurrentActor actor, UUID tagId);

    TagListResult list(CurrentActor actor, int page, int size);

    record TagListResult(List<TagData> items, long total) {
    }
}
```

- [ ] **Step 5: Красные тесты сервисов**

`src/test/java/com/plantarena/tournaments/application/TagsServiceTest.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.TagsUseCase;
import com.plantarena.tournaments.application.support.InMemoryTagRepository;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
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

@DisplayName("Справочник тегов: CRUD и права (раздел 13)")
class TagsServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final InMemoryTagRepository tags = new InMemoryTagRepository();
    private final TournamentRepository tournaments = new InMemoryTournamentRepository();
    private final TagsUseCase service = new TagsService(tags, tournaments,
        new TournamentsAccessPolicy(), Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID adminId = UUID.randomUUID();
    private final CurrentActor admin =
        CurrentActor.identified(adminId, Set.of(AppRole.USER, AppRole.ADMIN));
    private final CurrentActor moderator =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER, AppRole.MODERATOR));
    private final CurrentActor plainUser =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER));

    @Test
    @DisplayName("создание: модератор и админ; обычный пользователь — 403; дубль имени — 409")
    void создание_и_права() {
        assertThat(service.create(moderator, "  Комнатные  ").name()).isEqualTo("Комнатные");
        assertThat(service.create(admin, "Суккуленты").name()).isEqualTo("Суккуленты");

        assertThatThrownBy(() -> service.create(plainUser, "Нельзя"))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(admin, "Комнатные"))
            .isInstanceOf(TagAlreadyExistsException.class);
    }

    @Test
    @DisplayName("переименование: только админ; имя другого тега занято")
    void переименование() {
        UUID tagId = service.create(admin, "Комнатные").id();
        service.create(admin, "Суккуленты");

        assertThatThrownBy(() -> service.rename(moderator, tagId, "Кактусы"))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(service.rename(admin, tagId, "Кактусы").name()).isEqualTo("Кактусы");
        assertThatThrownBy(() -> service.rename(admin, tagId, "Суккуленты"))
            .isInstanceOf(TagAlreadyExistsException.class);
        assertThatThrownBy(() -> service.rename(admin, UUID.randomUUID(), "X"))
            .isInstanceOf(TagNotFoundException.class);
    }

    @Test
    @DisplayName("удаление: используемый турниром тег — 409, свободный — удаляется")
    void удаление() {
        UUID usedTag = service.create(admin, "Используемый").id();
        UUID freeTag = service.create(admin, "Свободный").id();
        tournaments.save(Tournament.createDraft(adminId, "Т", null,
            NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, Set.of(usedTag), NOW));

        assertThatThrownBy(() -> service.delete(admin, usedTag))
            .isInstanceOf(TagInUseException.class);

        service.delete(admin, freeTag);
        assertThatThrownBy(() -> service.get(admin, freeTag))
            .isInstanceOf(TagNotFoundException.class);
    }

    @Test
    @DisplayName("список: пагинация и total")
    void список() {
        service.create(admin, "Один");
        service.create(admin, "Два");
        service.create(admin, "Три");

        TagsUseCase.TagListResult page = service.list(admin, 1, 2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(3);
    }
}
```

`src/test/java/com/plantarena/tournaments/application/TournamentAdministrationServiceTest.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.CreateTournamentUseCase;
import com.plantarena.tournaments.application.port.in.DeleteTournamentUseCase;
import com.plantarena.tournaments.application.port.in.OpenRegistrationUseCase;
import com.plantarena.tournaments.application.port.in.UpdateTournamentUseCase;
import com.plantarena.tournaments.application.support.FakePlantEligibilityGateway;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTagRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.TournamentStatus;
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

@DisplayName("Администрация турнира: черновик, параметры, открытие, отмена (раздел 7)")
class TournamentAdministrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations = new InMemoryInvitationRepository();
    private final InMemoryTagRepository tags = new InMemoryTagRepository();
    private final FakePlantEligibilityGateway eligibility = new FakePlantEligibilityGateway();
    private final TournamentAdministrationService service = new TournamentAdministrationService(
        tournaments, tags, invitations, eligibility, new TournamentsAccessPolicy(),
        Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID organizerId = UUID.randomUUID();
    private final CurrentActor organizer =
        CurrentActor.identified(organizerId, Set.of(AppRole.USER, AppRole.MODERATOR));
    private final CurrentActor stranger =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER));

    private UUID createDraft() {
        return service.create(organizer, new CreateTournamentUseCase.CreateTournamentCommand(
            "Турнир", "Описание", NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2,
            Set.of())).id();
    }

    @Test
    @DisplayName("создание: модератор; обычный пользователь — 403; неизвестный тег — 404")
    void создание() {
        assertThat(service.create(organizer, new CreateTournamentUseCase.CreateTournamentCommand(
                "Турнир", "Описание", NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2,
                Set.of())).status()).isEqualTo("DRAFT");

        assertThatThrownBy(() -> service.create(stranger,
            new CreateTournamentUseCase.CreateTournamentCommand(
                "Турнир", null, NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, Set.of())))
            .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> service.create(organizer,
            new CreateTournamentUseCase.CreateTournamentCommand(
                "Турнир", null, NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2,
                Set.of(UUID.randomUUID()))))
            .isInstanceOf(TagNotFoundException.class);
    }

    @Test
    @DisplayName("update: параметры в DRAFT; после открытия параметры 409, описание 200")
    void update_правила() {
        UUID tournamentId = createDraft();
        service.update(organizer, tournamentId, new UpdateTournamentUseCase.UpdateTournamentCommand(
            "Новое имя", null, null, null, null, null, null));
        assertThat(tournaments.findById(tournamentId).orElseThrow().name())
            .isEqualTo("Новое имя");

        service.openRegistration(organizer, tournamentId);
        assertThatThrownBy(() -> service.update(organizer, tournamentId,
            new UpdateTournamentUseCase.UpdateTournamentCommand("Н", null, null, null,
                null, 3, null)))
            .isInstanceOf(TournamentStateConflictException.class);

        service.update(organizer, tournamentId,
            new UpdateTournamentUseCase.UpdateTournamentCommand(null, "Безопасное", null,
                null, null, null, null));
        assertThat(tournaments.findById(tournamentId).orElseThrow().description())
            .isEqualTo("Безопасное");

        assertThatThrownBy(() -> service.update(stranger, tournamentId,
            new UpdateTournamentUseCase.UpdateTournamentCommand(null, "Чужое", null,
                null, null, null, null)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("delete: пустой DRAFT удаляется; с приглашениями или после открытия — 409")
    void delete_правила() {
        UUID empty = createDraft();
        service.delete(organizer, empty);
        assertThat(tournaments.findById(empty)).isEmpty();

        UUID withInvitations = createDraft();
        invitations.save(Invitation.invite(withInvitations, UUID.randomUUID(),
            organizerId, NOW));
        assertThatThrownBy(() -> service.delete(organizer, withInvitations))
            .isInstanceOf(TournamentStateConflictException.class);

        UUID opened = createDraft();
        service.openRegistration(organizer, opened);
        assertThatThrownBy(() -> service.delete(organizer, opened))
            .isInstanceOf(TournamentStateConflictException.class);
    }

    @Test
    @DisplayName("openRegistration: DRAFT → REGISTRATION_OPEN; после дедлайна — 409")
    void open_registration() {
        UUID tournamentId = createDraft();
        service.openRegistration(organizer, tournamentId);
        assertThat(tournaments.findById(tournamentId).orElseThrow().status())
            .isEqualTo(TournamentStatus.REGISTRATION_OPEN);

        assertThatThrownBy(() -> service.openRegistration(organizer, tournamentId))
            .isInstanceOf(TournamentStateConflictException.class);
    }

    @Test
    @DisplayName("cancel: до RUNNING освобождает резервы принятых заявок; RUNNING — 409")
    void cancel_и_резервы() {
        UUID tournamentId = createDraft();
        UUID userId = UUID.randomUUID();
        Invitation accepted = invitations.save(Invitation.invite(tournamentId, userId,
            organizerId, NOW));
        accepted.accept(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true, NOW);
        invitations.save(accepted);

        service.cancel(organizer, tournamentId);
        assertThat(tournaments.findById(tournamentId).orElseThrow().status())
            .isEqualTo(TournamentStatus.CANCELLED);
        assertThat(eligibility.released).contains(accepted.reservationId());

        UUID runningId = createDraft();
        service.openRegistration(organizer, runningId);
        TournamentAdministrationServiceTest.start(tournaments, runningId, NOW);
        assertThatThrownBy(() -> service.cancel(organizer, runningId))
            .isInstanceOf(TournamentStateConflictException.class);
    }

    /** Помощник: перевод турнира в RUNNING напрямую через домен (для теста cancel). */
    private static void start(InMemoryTournamentRepository tournaments, UUID id, Instant now) {
        var tournament = tournaments.findById(id).orElseThrow();
        tournament.start(tournament.registrationDeadline(), 2);
        tournaments.save(tournament);
    }
}
```

`src/test/java/com/plantarena/tournaments/application/TournamentQueryServiceTest.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.ListTournamentsUseCase;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTagRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentStatus;
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

@DisplayName("Запросы турниров: доступность, фильтры, участники (раздел 13)")
class TournamentQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations = new InMemoryInvitationRepository();
    private final InMemoryTournamentEntryRepository entries =
        new InMemoryTournamentEntryRepository();
    private final TournamentQueryService service = new TournamentQueryService(tournaments,
        invitations, entries, new TournamentsAccessPolicy());

    private final UUID organizerId = UUID.randomUUID();
    private final CurrentActor organizer = CurrentActor.identified(organizerId, Set.of(AppRole.USER));
    private final CurrentActor admin =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER, AppRole.ADMIN));
    private final CurrentActor stranger =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER));

    private UUID newTournament(UUID tagId, Instant createdAt) {
        Tournament tournament = Tournament.restore(UUID.randomUUID(), organizerId, "Т", null,
            com.plantarena.tournaments.domain.TournamentType.PRIVATE,
            TournamentStatus.REGISTRATION_OPEN,
            com.plantarena.tournaments.domain.EliminationAlgorithmKind.ROUND_ELIMINATION,
            NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, null,
            tagId == null ? Set.of() : Set.of(tagId), createdAt, 0);
        return tournaments.save(tournament).id();
    }

    @Test
    @DisplayName("список: организатор видит свой, приглашённый — свой, чужой — нет, админ — все")
    void список_доступности() {
        UUID tournamentId = newTournament(null, NOW);
        UUID invitedUserId = UUID.randomUUID();
        invitations.save(Invitation.invite(tournamentId, invitedUserId, organizerId, NOW));

        assertThat(service.list(organizer, null, null, 0, 20).total()).isEqualTo(1);
        assertThat(service.list(CurrentActor.identified(invitedUserId, Set.of(AppRole.USER)),
            null, null, 0, 20).total()).isEqualTo(1);
        assertThat(service.list(stranger, null, null, 0, 20).total()).isZero();
        assertThat(service.list(admin, null, null, 0, 20).total()).isEqualTo(1);
    }

    @Test
    @DisplayName("список: фильтры статуса и тега; неизвестный статус — ошибка")
    void список_фильтры() {
        UUID tagId = UUID.randomUUID();
        newTournament(tagId, NOW);
        Tournament draft = Tournament.createDraft(organizerId, "Черновик", null,
            NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, Set.of(), NOW.plusSeconds(1));
        tournaments.save(draft);

        assertThat(service.list(organizer, "DRAFT", null, 0, 20).total())
            .isEqualTo(1);
        assertThat(service.list(organizer, null, tagId, 0, 20).total()).isEqualTo(1);
        assertThat(service.list(organizer, "RUNNING", tagId, 0, 20).total())
            .isZero();
        assertThatThrownBy(() -> service.list(organizer, "PAUSED", null, 0, 20))
            .isInstanceOf(UnknownStatusFilterException.class);
    }

    @Test
    @DisplayName("get: приглашённый и участник видят, чужой — 404")
    void get_доступ() {
        UUID tournamentId = newTournament(null, NOW);
        UUID invitedUserId = UUID.randomUUID();
        invitations.save(Invitation.invite(tournamentId, invitedUserId, organizerId, NOW));
        UUID participantId = UUID.randomUUID();
        entries.save(TournamentEntry.admit(tournamentId, participantId, UUID.randomUUID(),
            UUID.randomUUID(), NOW));

        assertThat(service.get(organizer, tournamentId).status()).isEqualTo("REGISTRATION_OPEN");
        assertThat(service.get(CurrentActor.identified(invitedUserId, Set.of(AppRole.USER)),
            tournamentId).id()).isEqualTo(tournamentId);
        assertThat(service.get(CurrentActor.identified(participantId, Set.of(AppRole.USER)),
            tournamentId).id()).isEqualTo(tournamentId);
        assertThatThrownBy(() -> service.get(stranger, tournamentId))
            .isInstanceOf(TournamentNotFoundException.class);
    }

    @Test
    @DisplayName("entries: участник видит список, чужой — 404")
    void entries_доступ() {
        UUID tournamentId = newTournament(null, NOW);
        UUID participantId = UUID.randomUUID();
        entries.save(TournamentEntry.admit(tournamentId, participantId, UUID.randomUUID(),
            UUID.randomUUID(), NOW));

        assertThat(service.list(CurrentActor.identified(participantId, Set.of(AppRole.USER)),
            tournamentId, 0, 20).total()).isEqualTo(1);
        assertThatThrownBy(() -> service.list(stranger, tournamentId, 0, 20))
            .isInstanceOf(TournamentNotFoundException.class);
    }
}
```

- [ ] **Step 6: Запустить — красный**

```bash
./mvnw -q test -Dtest='TagsServiceTest,TournamentAdministrationServiceTest,TournamentQueryServiceTest'
```

Ожидание: FAIL (компиляция): `cannot find symbol: class TagsService` и in-memory репозиториев.

- [ ] **Step 7: In-memory фейки**

`src/test/java/com/plantarena/tournaments/application/support/InMemoryTournamentRepository.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory фейк TournamentRepository: search честно повторяет семантику
 * «доступные» (организатор/активное приглашение/участие; админ — все),
 * как и JPA-реализация (контрактные тесты — Task 6).
 */
public class InMemoryTournamentRepository implements TournamentRepository {

    public final Map<UUID, Tournament> tournaments = new ConcurrentHashMap<>();
    public final Map<UUID, List<UUID>> invitationsByTournament = new ConcurrentHashMap<>();
    public final Map<UUID, List<UUID>> activeInvitationUsers = new ConcurrentHashMap<>();
    public final Map<UUID, List<UUID>> entryUsers = new ConcurrentHashMap<>();

    /** Регистрация связей для search (вызывается InMemoryInvitationRepository/Entry). */
    public void trackInvitation(UUID tournamentId, UUID userId, InvitationStatus status) {
        List<UUID> users = activeInvitationUsers.computeIfAbsent(tournamentId,
            key -> new java.util.concurrent.CopyOnWriteArrayList<>());
        if (status == InvitationStatus.INVITED || status == InvitationStatus.READY
                || status == InvitationStatus.ACCEPTED_PENDING_MODERATION) {
            if (!users.contains(userId)) {
                users.add(userId);
            }
        } else {
            users.remove(userId);
        }
    }

    public void trackEntry(UUID tournamentId, UUID userId) {
        entryUsers.computeIfAbsent(tournamentId,
                key -> new java.util.concurrent.CopyOnWriteArrayList<>())
            .add(userId);
    }

    @Override
    public Tournament save(Tournament tournament) {
        tournaments.put(tournament.id(), tournament);
        return tournament;
    }

    @Override
    public Optional<Tournament> findById(UUID id) {
        return Optional.ofNullable(tournaments.get(id));
    }

    @Override
    public void delete(UUID id) {
        tournaments.remove(id);
    }

    @Override
    public List<Tournament> search(TournamentFilter filter) {
        return filtered(filter)
            .sorted(Comparator.comparing(Tournament::createdAt).reversed()
                .thenComparing(Tournament::id))
            .skip(filter.offset())
            .limit(filter.size())
            .toList();
    }

    @Override
    public long count(TournamentFilter filter) {
        return filtered(filter).count();
    }

    @Override
    public List<Tournament> findDueForStart(Instant now, int limit) {
        return tournaments.values().stream()
            .filter(tournament -> tournament.status() == TournamentStatus.REGISTRATION_OPEN
                && !tournament.registrationDeadline().isAfter(now))
            .sorted(Comparator.comparing(Tournament::registrationDeadline)
                .thenComparing(Tournament::id))
            .limit(limit)
            .toList();
    }

    private java.util.stream.Stream<Tournament> filtered(TournamentFilter filter) {
        return tournaments.values().stream()
            .filter(tournament -> filter.admin()
                || filter.userId() == null // системный вызов без пользователя
                || tournament.creatorId().equals(filter.userId())
                || activeInvitationUsers.getOrDefault(tournament.id(), List.of())
                    .contains(filter.userId())
                || entryUsers.getOrDefault(tournament.id(), List.of())
                    .contains(filter.userId()))
            .filter(tournament -> filter.status() == null
                || tournament.status() == filter.status())
            .filter(tournament -> filter.tagId() == null
                || tournament.tagIds().contains(filter.tagId()));
    }
}
```

`src/test/java/com/plantarena/tournaments/application/support/InMemoryInvitationRepository.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * In-memory фейк InvitationRepository: честен к UNIQUE(tournamentId, userId)
 * (дубль — DataIntegrityViolationException, как БД).
 */
public class InMemoryInvitationRepository implements InvitationRepository {

    public final Map<UUID, Invitation> invitations = new ConcurrentHashMap<>();
    private final InMemoryTournamentRepository tournaments;

    public InMemoryInvitationRepository(InMemoryTournamentRepository tournaments) {
        this.tournaments = tournaments;
    }

    @Override
    public Invitation save(Invitation invitation) {
        invitations.values().stream()
            .filter(existing -> existing.tournamentId().equals(invitation.tournamentId())
                && existing.userId().equals(invitation.userId())
                && !existing.id().equals(invitation.id()))
            .findAny()
            .ifPresent(existing -> {
                throw new DataIntegrityViolationException(
                    "Приглашение пары уже существует: " + existing.id());
            });
        invitations.put(invitation.id(), invitation);
        if (tournaments != null) {
            tournaments.trackInvitation(invitation.tournamentId(), invitation.userId(),
                invitation.status());
        }
        return invitation;
    }

    @Override
    public Optional<Invitation> findById(UUID id) {
        return Optional.ofNullable(invitations.get(id));
    }

    @Override
    public Optional<Invitation> findByTournamentIdAndUserId(UUID tournamentId, UUID userId) {
        return invitations.values().stream()
            .filter(invitation -> invitation.tournamentId().equals(tournamentId)
                && invitation.userId().equals(userId))
            .findFirst();
    }

    @Override
    public Optional<Invitation> findBySubmittedPlantIdAndStatus(UUID plantId,
                                                                InvitationStatus status) {
        return invitations.values().stream()
            .filter(invitation -> status.equals(invitation.status())
                && plantId.equals(invitation.submittedPlantId()))
            .findFirst();
    }

    @Override
    public List<Invitation> findByTournamentId(UUID tournamentId, int offset, int size) {
        return invitations.values().stream()
            .filter(invitation -> invitation.tournamentId().equals(tournamentId))
            .sorted(Comparator.comparing(Invitation::id))
            .skip(offset).limit(size).toList();
    }

    @Override
    public long countByTournamentId(UUID tournamentId) {
        return invitations.values().stream()
            .filter(invitation -> invitation.tournamentId().equals(tournamentId))
            .count();
    }

    @Override
    public List<Invitation> findByUserId(UUID userId, int offset, int size) {
        return invitations.values().stream()
            .filter(invitation -> invitation.userId().equals(userId))
            .sorted(Comparator.comparing(Invitation::id))
            .skip(offset).limit(size).toList();
    }

    @Override
    public long countByUserId(UUID userId) {
        return invitations.values().stream()
            .filter(invitation -> invitation.userId().equals(userId))
            .count();
    }

    @Override
    public List<Invitation> findByTournamentIdAndStatus(UUID tournamentId,
                                                        InvitationStatus status) {
        return invitations.values().stream()
            .filter(invitation -> invitation.tournamentId().equals(tournamentId)
                && invitation.status() == status)
            .sorted(Comparator.comparing(Invitation::id))
            .toList();
    }

    @Override
    public boolean existsByTournamentId(UUID tournamentId) {
        return invitations.values().stream()
            .anyMatch(invitation -> invitation.tournamentId().equals(tournamentId));
    }
}
```

`src/test/java/com/plantarena/tournaments/application/support/InMemoryTournamentEntryRepository.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataIntegrityViolationException;

/** In-memory фейк TournamentEntryRepository: честен к UNIQUE(tournamentId, userId). */
public class InMemoryTournamentEntryRepository implements TournamentEntryRepository {

    public final Map<UUID, TournamentEntry> entries = new ConcurrentHashMap<>();
    private final InMemoryTournamentRepository tournaments;

    public InMemoryTournamentEntryRepository(InMemoryTournamentRepository tournaments) {
        this.tournaments = tournaments;
    }

    @Override
    public TournamentEntry save(TournamentEntry entry) {
        entries.values().stream()
            .filter(existing -> existing.tournamentId().equals(entry.tournamentId())
                && existing.userId().equals(entry.userId())
                && !existing.id().equals(entry.id()))
            .findAny()
            .ifPresent(existing -> {
                throw new DataIntegrityViolationException(
                    "Участие пары уже существует: " + existing.id());
            });
        entries.put(entry.id(), entry);
        if (tournaments != null) {
            tournaments.trackEntry(entry.tournamentId(), entry.userId());
        }
        return entry;
    }

    @Override
    public List<TournamentEntry> findByTournamentId(UUID tournamentId, int offset, int size) {
        return entries.values().stream()
            .filter(entry -> entry.tournamentId().equals(tournamentId))
            .sorted(Comparator.comparing(TournamentEntry::id))
            .skip(offset).limit(size).toList();
    }

    @Override
    public long countByTournamentId(UUID tournamentId) {
        return entries.values().stream()
            .filter(entry -> entry.tournamentId().equals(tournamentId))
            .count();
    }

    @Override
    public boolean existsByTournamentIdAndUserId(UUID tournamentId, UUID userId) {
        return entries.values().stream()
            .anyMatch(entry -> entry.tournamentId().equals(tournamentId)
                && entry.userId().equals(userId));
    }
}
```

`src/test/java/com/plantarena/tournaments/application/support/InMemoryTagRepository.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.domain.Tag;
import com.plantarena.tournaments.domain.TagRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory фейк TagRepository: isUsedByTournament — по tagIds турниров. */
public class InMemoryTagRepository implements TagRepository {

    public final Map<UUID, Tag> tags = new ConcurrentHashMap<>();
    private final InMemoryTournamentRepository tournaments;

    public InMemoryTagRepository() {
        this(null);
    }

    public InMemoryTagRepository(InMemoryTournamentRepository tournaments) {
        this.tournaments = tournaments;
    }

    @Override
    public Tag save(Tag tag) {
        tags.put(tag.id(), tag);
        return tag;
    }

    @Override
    public Optional<Tag> findById(UUID id) {
        return Optional.ofNullable(tags.get(id));
    }

    @Override
    public Optional<Tag> findByName(String name) {
        return tags.values().stream()
            .filter(tag -> tag.name().equals(name))
            .findFirst();
    }

    @Override
    public List<Tag> findAll(int offset, int size) {
        return tags.values().stream()
            .sorted(Comparator.comparing(Tag::name))
            .skip(offset).limit(size).toList();
    }

    @Override
    public long count() {
        return tags.size();
    }

    @Override
    public void delete(UUID id) {
        tags.remove(id);
    }

    @Override
    public boolean isUsedByTournament(UUID tagId) {
        return tournaments != null && tournaments.tournaments.values().stream()
            .anyMatch(tournament -> tournament.tagIds().contains(tagId));
    }
}
```

`src/test/java/com/plantarena/tournaments/application/support/FakePlantEligibilityGateway.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.application.ImageAlreadyReservedException;
import com.plantarena.tournaments.application.PlantNotReservableException;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Фейк порта допуска растений: записи вызовов + управляемые отказы. */
public class FakePlantEligibilityGateway implements PlantEligibilityGateway {

    public final Map<UUID, UUID> reservationsByKey = new HashMap<>();
    public final List<ConfirmCall> confirmed = new ArrayList<>();
    public final List<UUID> released = new ArrayList<>();
    public boolean conflictOnReserve;
    public boolean failOnReserve;
    public boolean failOnConfirm;

    @Override
    public UUID reserve(UUID ownerId, UUID plantId, UUID idempotencyKey) {
        if (conflictOnReserve) {
            throw new ImageAlreadyReservedException("Изображение уже зарезервировано (фейк)");
        }
        if (failOnReserve) {
            throw new PlantNotReservableException("Растение не проходит проверки (фейк)", null);
        }
        UUID reservationId = UUID.randomUUID();
        reservationsByKey.put(idempotencyKey, reservationId);
        return reservationId;
    }

    @Override
    public void confirm(UUID ownerId, UUID plantId, UUID reservationId) {
        if (failOnConfirm) {
            throw new PlantNotReservableException("Резерв недействителен (фейк)", null);
        }
        confirmed.add(new ConfirmCall(ownerId, plantId, reservationId));
    }

    @Override
    public void release(UUID reservationId) {
        released.add(reservationId);
    }

    public boolean isReleased(UUID reservationId) {
        return released.contains(reservationId);
    }

    public record ConfirmCall(UUID ownerId, UUID plantId, UUID reservationId) {
    }
}
```

- [ ] **Step 8: Реализация сервисов**

`src/main/java/com/plantarena/tournaments/application/TagsService.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TagData;
import com.plantarena.tournaments.application.port.in.TagsUseCase;
import com.plantarena.tournaments.domain.Tag;
import com.plantarena.tournaments.domain.TagRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Справочник тегов (раздел 13): создание — M/A, изменение/удаление — A,
 * используемый тег не удаляется (409).
 */
@Service
public class TagsService implements TagsUseCase {

    private final TagRepository tags;
    private final TournamentRepository tournaments;
    private final TournamentsAccessPolicy accessPolicy;
    private final Clock clock;

    public TagsService(TagRepository tags, TournamentRepository tournaments,
                       TournamentsAccessPolicy accessPolicy, Clock clock) {
        this.tags = tags;
        this.tournaments = tournaments;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TagData create(CurrentActor actor, String name) {
        accessPolicy.requireModeratorOrAdmin(actor);
        requireFreeName(name, null);
        return TournamentAssembler.toData(tags.save(Tag.create(name, clock.instant())));
    }

    @Override
    @Transactional
    public TagData rename(CurrentActor actor, UUID tagId, String name) {
        accessPolicy.requireAdmin(actor);
        Tag tag = tags.findById(tagId)
            .orElseThrow(() -> new TagNotFoundException("Тег не найден: " + tagId));
        requireFreeName(name, tagId);
        tag.rename(name);
        return TournamentAssembler.toData(tags.save(tag));
    }

    @Override
    @Transactional
    public void delete(CurrentActor actor, UUID tagId) {
        accessPolicy.requireAdmin(actor);
        Tag tag = tags.findById(tagId)
            .orElseThrow(() -> new TagNotFoundException("Тег не найден: " + tagId));
        if (tags.isUsedByTournament(tag.id())) {
            throw new TagInUseException("Тег используется турниром: " + tag.name());
        }
        tags.delete(tag.id());
    }

    @Override
    @Transactional(readOnly = true)
    public TagData get(CurrentActor actor, UUID tagId) {
        accessPolicy.requireIdentified(actor);
        return TournamentAssembler.toData(tags.findById(tagId)
            .orElseThrow(() -> new TagNotFoundException("Тег не найден: " + tagId)));
    }

    @Override
    @Transactional(readOnly = true)
    public TagListResult list(CurrentActor actor, int page, int size) {
        accessPolicy.requireIdentified(actor);
        int offset = page * size;
        List<TagData> items = tags.findAll(offset, size).stream()
            .map(TournamentAssembler::toData)
            .toList();
        return new TagListResult(items, tags.count());
    }

    private void requireFreeName(String name, UUID exceptTagId) {
        tags.findByName(name == null ? "" : name.trim())
            .filter(tag -> !tag.id().equals(exceptTagId))
            .ifPresent(tag -> {
                throw new TagAlreadyExistsException("Тег с именем уже существует: " + tag.name());
            });
    }
}
```

`src/main/java/com/plantarena/tournaments/application/TournamentAdministrationService.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import com.plantarena.tournaments.application.port.in.CancelTournamentUseCase;
import com.plantarena.tournaments.application.port.in.CreateTournamentUseCase;
import com.plantarena.tournaments.application.port.in.DeleteTournamentUseCase;
import com.plantarena.tournaments.application.port.in.OpenRegistrationUseCase;
import com.plantarena.tournaments.application.port.in.UpdateTournamentUseCase;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.TagRepository;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.TournamentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Администрация турнира (раздел 7): черновик, параметры (только DRAFT),
 * безопасное описание, удаление пустого черновика, открытие регистрации,
 * отмена до RUNNING. Отмена освобождает резервы принятых заявок командой
 * plants в той же tx (ADR-010).
 */
@Service
public class TournamentAdministrationService implements CreateTournamentUseCase,
        UpdateTournamentUseCase, DeleteTournamentUseCase, OpenRegistrationUseCase,
        CancelTournamentUseCase {

    private final TournamentRepository tournaments;
    private final TagRepository tags;
    private final InvitationRepository invitations;
    private final PlantEligibilityGateway eligibility;
    private final TournamentsAccessPolicy accessPolicy;
    private final Clock clock;

    public TournamentAdministrationService(TournamentRepository tournaments,
                                           TagRepository tags,
                                           InvitationRepository invitations,
                                           PlantEligibilityGateway eligibility,
                                           TournamentsAccessPolicy accessPolicy, Clock clock) {
        this.tournaments = tournaments;
        this.tags = tags;
        this.invitations = invitations;
        this.eligibility = eligibility;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TournamentData create(CurrentActor actor, CreateTournamentCommand command) {
        accessPolicy.requireModeratorOrAdmin(actor);
        Set<UUID> tagIds = resolveTags(command.tagIds());
        Tournament draft = Tournament.createDraft(actor.userId(), command.name(),
            command.description(), command.registrationDeadline(), command.roundDuration(),
            command.eliminationFraction(), command.minParticipants(), tagIds, clock.instant());
        return TournamentAssembler.toData(tournaments.save(draft));
    }

    @Override
    @Transactional
    public TournamentData update(CurrentActor actor, UUID tournamentId,
                                 UpdateTournamentCommand command) {
        accessPolicy.requireIdentified(actor);
        Tournament tournament = find(tournamentId);
        accessPolicy.requireOrganizer(actor, tournament);
        if (command.description() != null) {
            tournament.updateDescription(command.description());
        }
        if (isParameterUpdate(command)) {
            requireDraft(tournament, "Параметры меняются только в DRAFT");
            tournament.updateParameters(
                command.name() == null ? tournament.name() : command.name(),
                command.registrationDeadline() == null
                    ? tournament.registrationDeadline() : command.registrationDeadline(),
                command.roundDuration() == null
                    ? tournament.roundDuration() : command.roundDuration(),
                command.eliminationFraction() == null
                    ? tournament.eliminationFraction() : command.eliminationFraction(),
                command.minParticipants() == null
                    ? tournament.minParticipants() : command.minParticipants(),
                clock.instant());
        }
        if (command.tagIds() != null) {
            requireDraft(tournament, "Теги меняются только в DRAFT");
            tournament.replaceTags(resolveTags(command.tagIds()));
        }
        return TournamentAssembler.toData(tournaments.save(tournament));
    }

    @Override
    @Transactional
    public void delete(CurrentActor actor, UUID tournamentId) {
        accessPolicy.requireIdentified(actor);
        Tournament tournament = find(tournamentId);
        accessPolicy.requireOrganizer(actor, tournament);
        requireDraft(tournament, "Удалять можно только черновик");
        if (invitations.existsByTournamentId(tournamentId)) {
            throw new TournamentStateConflictException(
                "Черновик с приглашениями не удаляется: отзови приглашения или отмени турнир");
        }
        tournaments.delete(tournamentId);
    }

    @Override
    @Transactional
    public TournamentData openRegistration(CurrentActor actor, UUID tournamentId) {
        accessPolicy.requireIdentified(actor);
        Tournament tournament = find(tournamentId);
        accessPolicy.requireOrganizer(actor, tournament);
        if (tournament.status() != TournamentStatus.DRAFT) {
            throw new TournamentStateConflictException(
                "Открыть регистрацию можно только из DRAFT, текущий статус: "
                    + tournament.status());
        }
        if (!clock.instant().isBefore(tournament.registrationDeadline())) {
            throw new TournamentStateConflictException("Нельзя открыть регистрацию после дедлайна");
        }
        tournament.openRegistration(clock.instant());
        return TournamentAssembler.toData(tournaments.save(tournament));
    }

    @Override
    @Transactional
    public TournamentData cancel(CurrentActor actor, UUID tournamentId) {
        accessPolicy.requireIdentified(actor);
        Tournament tournament = find(tournamentId);
        accessPolicy.requireOrganizer(actor, tournament);
        if (tournament.status() == TournamentStatus.RUNNING
                || tournament.status() == TournamentStatus.FINISHED) {
            throw new TournamentStateConflictException(
                "Отмена активного турнира в первой версии запрещена (раздел 7)");
        }
        if (tournament.status() == TournamentStatus.CANCELLED) {
            throw new TournamentStateConflictException("Турнир уже отменён");
        }
        tournament.cancel(clock.instant());
        tournaments.save(tournament);
        releaseAcceptedReservations(tournamentId);
        return TournamentAssembler.toData(tournament);
    }

    /** Освободить резервы принятых заявок (отмена — ADR-010, та же tx). */
    private void releaseAcceptedReservations(UUID tournamentId) {
        for (InvitationStatus status : List.of(InvitationStatus.ACCEPTED_PENDING_MODERATION,
            InvitationStatus.READY)) {
            for (Invitation invitation
                    : invitations.findByTournamentIdAndStatus(tournamentId, status)) {
                if (invitation.reservationId() != null) {
                    eligibility.release(invitation.reservationId());
                }
            }
        }
    }

    private Set<UUID> resolveTags(Set<UUID> tagIds) {
        Set<UUID> resolved = new HashSet<>();
        for (UUID tagId : tagIds == null ? Set.<UUID>of() : tagIds) {
            tags.findById(tagId).orElseThrow(
                () -> new TagNotFoundException("Тег не найден: " + tagId));
            resolved.add(tagId);
        }
        return resolved;
    }

    private boolean isParameterUpdate(UpdateTournamentCommand command) {
        return command.name() != null || command.registrationDeadline() != null
            || command.roundDuration() != null || command.eliminationFraction() != null
            || command.minParticipants() != null;
    }

    private void requireDraft(Tournament tournament, String message) {
        if (tournament.status() != TournamentStatus.DRAFT) {
            throw new TournamentStateConflictException(message + ", текущий статус: "
                + tournament.status());
        }
    }

    private Tournament find(UUID tournamentId) {
        return tournaments.findById(tournamentId)
            .orElseThrow(() -> new TournamentNotFoundException("Турнир не найден: " + tournamentId));
    }
}
```

`src/main/java/com/plantarena/tournaments/application/TournamentQueryService.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.EntryData;
import com.plantarena.tournaments.api.TournamentData;
import com.plantarena.tournaments.application.port.in.GetTournamentUseCase;
import com.plantarena.tournaments.application.port.in.ListEntriesUseCase;
import com.plantarena.tournaments.application.port.in.ListTournamentsUseCase;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.TournamentStatus;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Запросы турниров (раздел 13): доступные списки с фильтрами, просмотр
 * (организатор/админ/активное приглашение/участие), участники.
 */
@Service
@Transactional(readOnly = true)
public class TournamentQueryService implements ListTournamentsUseCase, GetTournamentUseCase,
        ListEntriesUseCase {

    private static final Set<InvitationStatus> ACTIVE_INVITATION_STATUSES = Set.of(
        InvitationStatus.INVITED, InvitationStatus.ACCEPTED_PENDING_MODERATION,
        InvitationStatus.READY);

    private final TournamentRepository tournaments;
    private final InvitationRepository invitations;
    private final TournamentEntryRepository entries;
    private final TournamentsAccessPolicy accessPolicy;

    public TournamentQueryService(TournamentRepository tournaments,
                                  InvitationRepository invitations,
                                  TournamentEntryRepository entries,
                                  TournamentsAccessPolicy accessPolicy) {
        this.tournaments = tournaments;
        this.invitations = invitations;
        this.entries = entries;
        this.accessPolicy = accessPolicy;
    }

    @Override
    public TournamentListResult list(CurrentActor actor, String status, UUID tagId,
                                     int page, int size) {
        accessPolicy.requireIdentified(actor);
        TournamentRepository.TournamentFilter filter =
            new TournamentRepository.TournamentFilter(actor.userId(), actor.hasRole(
                com.plantarena.shared.security.AppRole.ADMIN), parseStatus(status), tagId,
                page * size, size);
        List<TournamentData> items = tournaments.search(filter).stream()
            .map(TournamentAssembler::toData)
            .toList();
        return new TournamentListResult(items, tournaments.count(filter));
    }

    /** Фильтр статуса: строка опубликованного языка → домен; неизвестное — 400. */
    private TournamentStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return TournamentStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new UnknownStatusFilterException(
                "Неизвестный статус турнира: " + status
                    + " (допустимо: DRAFT, REGISTRATION_OPEN, RUNNING, FINISHED, CANCELLED)");
        }
    }

    @Override
    public TournamentData get(CurrentActor actor, UUID tournamentId) {
        Tournament tournament = find(tournamentId);
        accessPolicy.requireTournamentViewer(actor, tournament,
            visibleBeyondOrganizer(actor, tournamentId));
        return TournamentAssembler.toData(tournament);
    }

    @Override
    public EntryListResult list(CurrentActor actor, UUID tournamentId, int page, int size) {
        Tournament tournament = find(tournamentId);
        accessPolicy.requireTournamentViewer(actor, tournament,
            visibleBeyondOrganizer(actor, tournamentId));
        List<EntryData> items = entries.findByTournamentId(tournamentId, page * size, size)
            .stream()
            .map(TournamentAssembler::toData)
            .toList();
        return new EntryListResult(items, entries.countByTournamentId(tournamentId));
    }

    private boolean visibleBeyondOrganizer(CurrentActor actor, UUID tournamentId) {
        boolean invited = invitations.findByTournamentIdAndUserId(tournamentId, actor.userId())
            .map(invitation -> ACTIVE_INVITATION_STATUSES.contains(invitation.status()))
            .orElse(false);
        return invited || entries.existsByTournamentIdAndUserId(tournamentId, actor.userId());
    }

    private Tournament find(UUID tournamentId) {
        return tournaments.findById(tournamentId)
            .orElseThrow(() -> new TournamentNotFoundException("Турнир не найден: " + tournamentId));
    }
}
```

- [ ] **Step 9: Запустить — зелёный и Commit**

```bash
./mvnw -q test -Dtest='TagsServiceTest,TournamentAdministrationServiceTest,TournamentQueryServiceTest'
```

Ожидание: PASS (13 тестов). Затем `./mvnw -q test` — весь набор unit зелёный.

```bash
git add src/main/java/com/plantarena/tournaments/api src/main/java/com/plantarena/tournaments/application src/test/java/com/plantarena/tournaments/application
git commit -m "feat(tournaments): application — api-DTO, теги, администрация и запросы турниров, AccessPolicy"
```

---

### Task 5: Application — приглашения, реакция на модерацию, старт по дедлайну

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/api/event/TournamentStartedEvent.java`
- Create: `src/main/java/com/plantarena/tournaments/api/event/InvitationCreatedEvent.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/in/{InviteUserUseCase, RevokeInvitationUseCase, AcceptInvitationUseCase, DeclineInvitationUseCase, StartTournamentUseCase, OnPlantModerationDecidedUseCase}.java`
- Create: `src/main/java/com/plantarena/tournaments/application/{InvitationService, PlantModerationReactionService, StartTournamentService}.java`
- Create (test): `src/test/java/com/plantarena/tournaments/application/support/{FakePlantDirectoryGateway, FakeParticipantDirectoryGateway, FakeEventPublisher}.java`
- Test: `src/test/java/com/plantarena/tournaments/application/InvitationServiceTest.java`
- Test: `src/test/java/com/plantarena/tournaments/application/PlantModerationReactionServiceTest.java`
- Test: `src/test/java/com/plantarena/tournaments/application/StartTournamentServiceTest.java`

**Interfaces:**
- Consumes: домен и application Task 2–4, `IntegrationEventPublisher` (shared.event), `PlantEligibilityGateway`/`PlantDirectoryGateway`/`ParticipantDirectoryGateway` (Task 1), `TransactionTemplate` (Spring Boot auto-config).
- Produces (для Tasks 6–7): события `TournamentStartedEvent`/`InvitationCreatedEvent`; use case-порты (код ниже); `InvitationService`, `PlantModerationReactionService`, `StartTournamentService`.

- [ ] **Step 1: События api**

`src/main/java/com/plantarena/tournaments/api/event/TournamentStartedEvent.java`:

```java
package com.plantarena.tournaments.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованное событие: турнир стартовал (раздел 16). Подписчики —
 * итерации 6–8 (окна/лента) и notification-service лабы №4.
 */
public record TournamentStartedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "TournamentStarted";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID tournamentId, UUID creatorId) {
    }
}
```

`src/main/java/com/plantarena/tournaments/api/event/InvitationCreatedEvent.java`:

```java
package com.plantarena.tournaments.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованное событие: пользователя пригласили в турнир (раздел 16;
 * уведомления — лаба №4).
 */
public record InvitationCreatedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "InvitationCreated";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID invitationId, UUID tournamentId, UUID userId) {
    }
}
```

- [ ] **Step 2: Порты in**

`src/main/java/com/plantarena/tournaments/application/port/in/InviteUserUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.InvitationData;
import java.util.UUID;

/** Пригласить пользователя (организатор, до дедлайна; дубль пары — 409). */
public interface InviteUserUseCase {

    InvitationData invite(CurrentActor actor, UUID tournamentId, UUID userId);
}
```

`src/main/java/com/plantarena/tournaments/application/port/in/RevokeInvitationUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/** Отозвать не принятое приглашение (организатор, до дедлайна). */
public interface RevokeInvitationUseCase {

    void revoke(CurrentActor actor, UUID tournamentId, UUID invitationId);
}
```

`src/main/java/com/plantarena/tournaments/application/port/in/AcceptInvitationUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.InvitationData;
import java.util.UUID;

/**
 * Принять приглашение с растением (адресат, до дедлайна): резерв изображения
 * в той же tx (ADR-010); APPROVED → READY, иначе ACCEPTED_PENDING_MODERATION.
 */
public interface AcceptInvitationUseCase {

    InvitationData accept(CurrentActor actor, UUID invitationId, UUID plantId);
}
```

`src/main/java/com/plantarena/tournaments/application/port/in/DeclineInvitationUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.InvitationData;
import java.util.UUID;

/** Отказаться от приглашения до старта с освобождением резерва. */
public interface DeclineInvitationUseCase {

    InvitationData decline(CurrentActor actor, UUID invitationId);
}
```

`src/main/java/com/plantarena/tournaments/application/port/in/StartTournamentUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.time.Instant;
import java.util.UUID;

/**
 * Один use case старта для ручки и scheduler'а (раздел 7): start — ручка
 * организатора (только после дедлайна), startDue — обработка наступивших
 * дедлайнов (poller и demo-ручка). Идемпотентен по статусу турнира.
 */
public interface StartTournamentUseCase {

    TournamentData start(CurrentActor actor, UUID tournamentId);

    /** Обработать due-турниры; возвращает число запущенных/отменённых. */
    int startDue(Instant now, int limit);
}
```

`src/main/java/com/plantarena/tournaments/application/port/in/OnPlantModerationDecidedUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import java.util.UUID;

/**
 * Реакция на PlantModerationDecided (раздел 4.3): APPROVED → заявка READY
 * (если дедлайн не прошёл и резерв действителен), REJECTED → возврат в
 * INVITED с освобождением резерва. Вызывается синхронно в tx применения
 * решения модерации (раздел 10.3, ADR-010).
 */
public interface OnPlantModerationDecidedUseCase {

    void onPlantModerationDecided(UUID plantId, String decision);
}
```

- [ ] **Step 3: Красные тесты**

`src/test/java/com/plantarena/tournaments/application/InvitationServiceTest.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.AcceptInvitationUseCase;
import com.plantarena.tournaments.application.port.in.CreateTournamentUseCase;
import com.plantarena.tournaments.application.port.in.DeclineInvitationUseCase;
import com.plantarena.tournaments.application.port.in.InviteUserUseCase;
import com.plantarena.tournaments.application.port.in.OpenRegistrationUseCase;
import com.plantarena.tournaments.application.port.in.RevokeInvitationUseCase;
import com.plantarena.tournaments.application.port.out.ParticipantDirectoryGateway;
import com.plantarena.tournaments.application.port.out.PlantDirectoryGateway;
import com.plantarena.tournaments.application.support.FakeEventPublisher;
import com.plantarena.tournaments.application.support.FakeParticipantDirectoryGateway;
import com.plantarena.tournaments.application.support.FakePlantDirectoryGateway;
import com.plantarena.tournaments.application.support.FakePlantEligibilityGateway;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTagRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.api.event.InvitationCreatedEvent;
import com.plantarena.tournaments.domain.InvitationStatus;
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

@DisplayName("Приглашения: пригласить/отозвать/принять/отказаться (раздел 7)")
class InvitationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations =
        new InMemoryInvitationRepository(tournaments);
    private final FakePlantEligibilityGateway eligibility = new FakePlantEligibilityGateway();
    private final FakePlantDirectoryGateway plantDirectory = new FakePlantDirectoryGateway();
    private final FakeParticipantDirectoryGateway participants =
        new FakeParticipantDirectoryGateway();
    private final FakeEventPublisher events = new FakeEventPublisher();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TournamentsAccessPolicy accessPolicy = new TournamentsAccessPolicy();
    private final TournamentAdministrationService administration =
        new TournamentAdministrationService(tournaments, new InMemoryTagRepository(),
            invitations, eligibility, accessPolicy, clock);
    private final InvitationService service = new InvitationService(tournaments, invitations,
        eligibility, plantDirectory, participants, accessPolicy, events, clock);

    private final UUID organizerId = UUID.randomUUID();
    private final CurrentActor organizer =
        CurrentActor.identified(organizerId, Set.of(AppRole.USER, AppRole.MODERATOR));
    private final UUID userId = UUID.randomUUID();
    private final CurrentActor user = CurrentActor.identified(userId, Set.of(AppRole.USER));
    private final CurrentActor stranger =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER));

    private UUID openTournament() {
        UUID tournamentId = administration.create(organizer,
            new CreateTournamentUseCase.CreateTournamentCommand("Турнир", null,
                NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, Set.of())).id();
        administration.openRegistration(organizer, tournamentId);
        return tournamentId;
    }

    @Test
    @DisplayName("invite: INVITED + событие; дубль пары 409; неизвестный пользователь 404")
    void invite_правила() {
        UUID tournamentId = openTournament();
        participants.knownUsers.add(userId);

        var invitation = service.invite(organizer, tournamentId, userId);
        assertThat(invitation.status()).isEqualTo("INVITED");
        assertThat(events.published).anySatisfy(event ->
            assertThat(event).isInstanceOf(InvitationCreatedEvent.class));

        assertThatThrownBy(() -> service.invite(organizer, tournamentId, userId))
            .isInstanceOf(DuplicateInvitationException.class);
        assertThatThrownBy(() -> service.invite(organizer, tournamentId, UUID.randomUUID()))
            .isInstanceOf(UnknownUserException.class);
    }

    @Test
    @DisplayName("invite: после дедлайна — 409 RegistrationClosed")
    void invite_после_дедлайна() {
        UUID tournamentId = administration.create(organizer,
            new CreateTournamentUseCase.CreateTournamentCommand("Турнир", null,
                NOW.plusSeconds(1), Duration.ofHours(1), 0.5, 2, Set.of())).id();
        administration.openRegistration(organizer, tournamentId);
        participants.knownUsers.add(userId);

        // дедлайн прошёл: время фиксировано на NOW.plusSeconds(2)
        InvitationService lateService = new InvitationService(tournaments, invitations,
            eligibility, plantDirectory, participants, accessPolicy, events,
            Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));
        assertThatThrownBy(() -> lateService.invite(organizer, tournamentId, userId))
            .isInstanceOf(RegistrationClosedException.class);
    }

    @Test
    @DisplayName("accept: PENDING-растение → ACCEPTED_PENDING_MODERATION с резервом по свежему ключу")
    void accept_pending() {
        UUID tournamentId = openTournament();
        participants.knownUsers.add(userId);
        UUID invitationId = service.invite(organizer, tournamentId, userId).id();
        UUID plantId = UUID.randomUUID();
        plantDirectory.plants.put(plantId,
            new PlantDirectoryGateway.PlantSnapshot(plantId, userId, false));

        var accepted = service.accept(user, invitationId, plantId);

        assertThat(accepted.status()).isEqualTo("ACCEPTED_PENDING_MODERATION");
        assertThat(eligibility.reservationsByKey).hasSize(1);
        UUID reservationId = invitations.findById(invitationId).orElseThrow().reservationId();
        assertThat(eligibility.reservationsByKey).containsValue(reservationId);

        // идемпотентный повтор с тем же растением — без нового резерва
        service.accept(user, invitationId, plantId);
        assertThat(eligibility.reservationsByKey).hasSize(1);
    }

    @Test
    @DisplayName("accept: APPROVED-растение → сразу READY; чужое приглашение скрыто (404)")
    void accept_варианты() {
        UUID tournamentId = openTournament();
        participants.knownUsers.add(userId);
        UUID invitationId = service.invite(organizer, tournamentId, userId).id();
        UUID plantId = UUID.randomUUID();
        plantDirectory.plants.put(plantId,
            new PlantDirectoryGateway.PlantSnapshot(plantId, userId, true));

        assertThat(service.accept(user, invitationId, plantId).status()).isEqualTo("READY");

        // чужое приглашение скрыто: не адресат и посторонний получают 404
        UUID strangerInvitationId = service.invite(organizer, tournamentId,
            stranger.userId()).id();
        assertThatThrownBy(() -> service.accept(stranger, invitationId, plantId))
            .isInstanceOf(InvitationNotFoundException.class);
        assertThatThrownBy(() -> service.accept(user, strangerInvitationId, plantId))
            .isInstanceOf(InvitationNotFoundException.class);
    }

    @Test
    @DisplayName("accept: конфликт резерва и нерезервируемое растение — 409")
    void accept_конфликты() {
        UUID tournamentId = openTournament();
        participants.knownUsers.add(userId);
        UUID invitationId = service.invite(organizer, tournamentId, userId).id();
        UUID plantId = UUID.randomUUID();
        plantDirectory.plants.put(plantId,
            new PlantDirectoryGateway.PlantSnapshot(plantId, userId, true));

        eligibility.conflictOnReserve = true;
        assertThatThrownBy(() -> service.accept(user, invitationId, plantId))
            .isInstanceOf(ImageAlreadyReservedException.class);
        eligibility.conflictOnReserve = false;

        eligibility.failOnReserve = true;
        assertThatThrownBy(() -> service.accept(user, invitationId, plantId))
            .isInstanceOf(PlantNotReservableException.class);
    }

    @Test
    @DisplayName("decline: освобождает резерв; после старта — 409")
    void decline_правила() {
        UUID tournamentId = openTournament();
        participants.knownUsers.add(userId);
        UUID invitationId = service.invite(organizer, tournamentId, userId).id();
        UUID plantId = UUID.randomUUID();
        plantDirectory.plants.put(plantId,
            new PlantDirectoryGateway.PlantSnapshot(plantId, userId, false));
        service.accept(user, invitationId, plantId);
        UUID reservationId = invitations.findById(invitationId).orElseThrow().reservationId();

        assertThat(service.decline(user, invitationId).status()).isEqualTo("DECLINED");
        assertThat(eligibility.isReleased(reservationId)).isTrue();
    }

    @Test
    @DisplayName("revoke: только INVITED и до дедлайна; принятое — 409")
    void revoke_правила() {
        UUID tournamentId = openTournament();
        participants.knownUsers.add(userId);
        UUID invitationId = service.invite(organizer, tournamentId, userId).id();

        service.revoke(organizer, tournamentId, invitationId);
        assertThat(invitations.findById(invitationId).orElseThrow().status())
            .isEqualTo(InvitationStatus.REVOKED);

        UUID acceptedId = service.invite(organizer, tournamentId,
            UUID.randomUUID()).id();
        invitations.findById(acceptedId).orElseThrow()
            .accept(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true, NOW);
        assertThatThrownBy(() -> service.revoke(organizer, tournamentId, acceptedId))
            .isInstanceOf(TournamentStateConflictException.class);
    }
}
```

`src/test/java/com/plantarena/tournaments/application/PlantModerationReactionServiceTest.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.tournaments.application.port.in.CreateTournamentUseCase;
import com.plantarena.tournaments.application.support.FakePlantEligibilityGateway;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTagRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Реакция на PlantModerationDecided: READY / возврат в INVITED (раздел 7)")
class PlantModerationReactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations =
        new InMemoryInvitationRepository(tournaments);
    private final FakePlantEligibilityGateway eligibility = new FakePlantEligibilityGateway();
    private final TournamentsAccessPolicy accessPolicy = new TournamentsAccessPolicy();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TournamentAdministrationService administration =
        new TournamentAdministrationService(tournaments, new InMemoryTagRepository(),
            invitations, eligibility, accessPolicy, clock);
    private final PlantModerationReactionService service =
        new PlantModerationReactionService(tournaments, invitations, eligibility, clock);

    private final UUID organizerId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private Invitation acceptedPending(Instant registrationDeadline) {
        CurrentActor organizerActor = CurrentActor.identified(organizerId,
            Set.of(com.plantarena.shared.security.AppRole.USER,
                com.plantarena.shared.security.AppRole.MODERATOR));
        UUID tournamentId = administration.create(organizerActor,
            new CreateTournamentUseCase.CreateTournamentCommand("Турнир", null,
                registrationDeadline, Duration.ofHours(1), 0.5, 2, Set.of())).id();
        administration.openRegistration(organizerActor, tournamentId);
        Invitation invitation = invitations.save(Invitation.invite(tournamentId, userId,
            organizerId, NOW));
        invitation.accept(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false, NOW);
        return invitations.save(invitation);
    }

    @Test
    @DisplayName("APPROVED до дедлайна: резерв подтверждён, заявка READY")
    void approved_до_дедлайна() {
        Invitation invitation = acceptedPending(NOW.plusSeconds(3600));

        service.onPlantModerationDecided(invitation.submittedPlantId(), "APPROVED");

        assertThat(invitations.findById(invitation.id()).orElseThrow().status())
            .isEqualTo(InvitationStatus.READY);
        assertThat(eligibility.confirmed).hasSize(1);
    }

    @Test
    @DisplayName("REJECTED: возврат в INVITED, резерв освобождён, история подачи сохранена")
    void rejected_возврат() {
        Invitation invitation = acceptedPending(NOW.plusSeconds(3600));
        UUID reservationId = invitation.reservationId();
        UUID plantId = invitation.submittedPlantId();

        service.onPlantModerationDecided(plantId, "REJECTED");

        Invitation after = invitations.findById(invitation.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(InvitationStatus.INVITED);
        assertThat(after.submittedPlantId()).isEqualTo(plantId); // история последней подачи
        assertThat(eligibility.isReleased(reservationId)).isTrue();
    }

    @Test
    @DisplayName("APPROVED после дедлайна: заявка не меняется (терминальные правила раздела 7)")
    void approved_после_дедлайна() {
        Invitation invitation = acceptedPending(NOW.plusSeconds(10));
        PlantModerationReactionService lateService =
            new PlantModerationReactionService(tournaments, invitations, eligibility,
                Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC));

        lateService.onPlantModerationDecided(invitation.submittedPlantId(), "APPROVED");

        assertThat(invitations.findById(invitation.id()).orElseThrow().status())
            .isEqualTo(InvitationStatus.ACCEPTED_PENDING_MODERATION);
    }

    @Test
    @DisplayName("решение по растению без заявки — no-op")
    void без_заявки() {
        service.onPlantModerationDecided(UUID.randomUUID(), "APPROVED");
        assertThat(eligibility.confirmed).isEmpty();
        assertThat(eligibility.released).isEmpty();
    }
}
```

`src/test/java/com/plantarena/tournaments/application/StartTournamentServiceTest.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.event.TournamentStartedEvent;
import com.plantarena.tournaments.application.port.in.CreateTournamentUseCase;
import com.plantarena.tournaments.application.support.FakeEventPublisher;
import com.plantarena.tournaments.application.support.FakePlantEligibilityGateway;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTagRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.TournamentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Старт турнира: один use case для ручки и scheduler'а (раздел 7)")
class StartTournamentServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations =
        new InMemoryInvitationRepository(tournaments);
    private final InMemoryTournamentEntryRepository entries =
        new InMemoryTournamentEntryRepository(tournaments);
    private final FakePlantEligibilityGateway eligibility = new FakePlantEligibilityGateway();
    private final FakeEventPublisher events = new FakeEventPublisher();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TournamentsAccessPolicy accessPolicy = new TournamentsAccessPolicy();
    private final TournamentAdministrationService administration =
        new TournamentAdministrationService(tournaments, new InMemoryTagRepository(),
            invitations, eligibility, accessPolicy, clock);
    private final StartTournamentService service = new StartTournamentService(tournaments,
        invitations, entries, eligibility, accessPolicy, events, clock, transactionTemplate());

    private final UUID organizerId = UUID.randomUUID();
    private final CurrentActor organizer =
        CurrentActor.identified(organizerId, Set.of(AppRole.USER, AppRole.MODERATOR));

    /** TransactionTemplate без менеджера транзакций: выполняет действие сразу (unit). */
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate() {
        return new org.springframework.transaction.support.TransactionTemplate() {
            @Override
            public void executeWithoutResult(java.util.function.Consumer<
                    org.springframework.transaction.TransactionStatus> action) {
                action.accept(new org.springframework.transaction.support.SimpleTransactionStatus());
            }
        };
    }

    private UUID openTournament(Instant deadline) {
        UUID tournamentId = administration.create(organizer,
            new CreateTournamentUseCase.CreateTournamentCommand("Турнир", null, deadline,
                Duration.ofHours(1), 0.5, 2, Set.of())).id();
        administration.openRegistration(organizer, tournamentId);
        return tournamentId;
    }

    private Invitation readyInvitation(UUID tournamentId) {
        Invitation invitation = invitations.save(Invitation.invite(tournamentId,
            UUID.randomUUID(), organizerId, NOW));
        invitation.accept(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true, NOW);
        return invitations.save(invitation);
    }

    @Test
    @DisplayName("старт по дедлайну: RUNNING, entries по READY, не-READY EXPIRED, событие, резервы подтверждены")
    void старт_успешный() {
        UUID tournamentId = openTournament(NOW.plusSeconds(1));
        Invitation ready1 = readyInvitation(tournamentId);
        Invitation ready2 = readyInvitation(tournamentId);
        Invitation invitedOnly = invitations.save(Invitation.invite(tournamentId,
            UUID.randomUUID(), organizerId, NOW));

        var result = service.startDue(NOW.plusSeconds(2), 10);

        assertThat(result).isEqualTo(1);
        assertThat(tournaments.findById(tournamentId).orElseThrow().status())
            .isEqualTo(TournamentStatus.RUNNING);
        assertThat(entries.findByTournamentId(tournamentId, 0, 50)).hasSize(2);
        assertThat(eligibility.confirmed).extracting(call -> call.reservationId())
            .containsExactlyInAnyOrder(ready1.reservationId(), ready2.reservationId());
        assertThat(invitations.findById(invitedOnly.id()).orElseThrow().status())
            .isEqualTo(InvitationStatus.EXPIRED);
        assertThat(events.published).anySatisfy(event ->
            assertThat(event).isInstanceOf(TournamentStartedEvent.class));
    }

    @Test
    @DisplayName("недостаток участников: CANCELLED + INSUFFICIENT_PARTICIPANTS, резервы освобождены")
    void старт_недостаток() {
        UUID tournamentId = openTournament(NOW.plusSeconds(1));
        Invitation ready = readyInvitation(tournamentId);

        service.startDue(NOW.plusSeconds(2), 10);

        var tournament = tournaments.findById(tournamentId).orElseThrow();
        assertThat(tournament.status()).isEqualTo(TournamentStatus.CANCELLED);
        assertThat(tournament.cancelReason().name()).isEqualTo("INSUFFICIENT_PARTICIPANTS");
        assertThat(eligibility.isReleased(ready.reservationId())).isTrue();
        assertThat(entries.findByTournamentId(tournamentId, 0, 50)).isEmpty();
        assertThat(events.published).noneSatisfy(event ->
            assertThat(event).isInstanceOf(TournamentStartedEvent.class));
    }

    @Test
    @DisplayName("ручка: до дедлайна 409; после — RUNNING; повтор — 409 (идемпотентность scheduler'а — no-op)")
    void ручка_старта() {
        UUID tournamentId = openTournament(NOW.plusSeconds(3600));
        readyInvitation(tournamentId);
        readyInvitation(tournamentId);

        assertThatThrownBy(() -> service.start(organizer, tournamentId))
            .isInstanceOf(TournamentStateConflictException.class); // до дедлайна

        StartTournamentService afterDeadline = new StartTournamentService(tournaments,
            invitations, entries, eligibility, accessPolicy, events,
            Clock.fixed(NOW.plusSeconds(7200), ZoneOffset.UTC), transactionTemplate());
        assertThat(afterDeadline.start(organizer, tournamentId).status()).isEqualTo("RUNNING");

        assertThatThrownBy(() -> afterDeadline.start(organizer, tournamentId))
            .isInstanceOf(TournamentStateConflictException.class); // уже RUNNING
    }

    @Test
    @DisplayName("startDue: только due (deadline <= now); недедлайнные не тронуты; повтор — no-op")
    void startDue_фильтр_и_идемпотентность() {
        UUID dueTournament = openTournament(NOW.plusSeconds(1));
        readyInvitation(dueTournament);
        readyInvitation(dueTournament);
        UUID futureTournament = openTournament(NOW.plusSeconds(3600));
        readyInvitation(futureTournament);
        readyInvitation(futureTournament);

        assertThat(service.startDue(NOW.plusSeconds(2), 10)).isEqualTo(1);
        assertThat(tournaments.findById(futureTournament).orElseThrow().status())
            .isEqualTo(TournamentStatus.REGISTRATION_OPEN);

        assertThat(service.startDue(NOW.plusSeconds(3), 10)).isZero(); // уже обработан
    }
}
```

- [ ] **Step 4: Запустить — красный**

```bash
./mvnw -q test -Dtest='InvitationServiceTest,PlantModerationReactionServiceTest,StartTournamentServiceTest'
```

Ожидание: FAIL (компиляция): `cannot find symbol: class InvitationService` и др.

- [ ] **Step 5: Тестовые фейки портов**

`src/test/java/com/plantarena/tournaments/application/support/FakePlantDirectoryGateway.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.application.port.out.PlantDirectoryGateway;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Фейк read-порта растений: snapshot по plantId. */
public class FakePlantDirectoryGateway implements PlantDirectoryGateway {

    public final Map<UUID, PlantSnapshot> plants = new HashMap<>();

    @Override
    public Optional<PlantSnapshot> findById(UUID plantId) {
        return Optional.ofNullable(plants.get(plantId));
    }
}
```

`src/test/java/com/plantarena/tournaments/application/support/FakeParticipantDirectoryGateway.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.application.port.out.ParticipantDirectoryGateway;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Фейк каталога участников: известные пользователи. */
public class FakeParticipantDirectoryGateway implements ParticipantDirectoryGateway {

    public final Set<UUID> knownUsers = new HashSet<>();

    @Override
    public boolean isKnownUser(UUID userId) {
        return knownUsers.contains(userId);
    }
}
```

`src/test/java/com/plantarena/tournaments/application/support/FakeEventPublisher.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.shared.event.IntegrationEvent;
import com.plantarena.shared.event.IntegrationEventPublisher;
import java.util.ArrayList;
import java.util.List;

/** Фейк публикации событий: записи в списке. */
public class FakeEventPublisher implements IntegrationEventPublisher {

    public final List<IntegrationEvent> published = new ArrayList<>();

    @Override
    public void publish(IntegrationEvent event) {
        published.add(event);
    }
}
```

- [ ] **Step 6: Реализация InvitationService**

`src/main/java/com/plantarena/tournaments/application/InvitationService.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.event.IntegrationEventPublisher;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.InvitationData;
import com.plantarena.tournaments.api.event.InvitationCreatedEvent;
import com.plantarena.tournaments.application.port.in.AcceptInvitationUseCase;
import com.plantarena.tournaments.application.port.in.DeclineInvitationUseCase;
import com.plantarena.tournaments.application.port.in.InviteUserUseCase;
import com.plantarena.tournaments.application.port.in.ListInvitationsUseCase;
import com.plantarena.tournaments.application.port.in.RevokeInvitationUseCase;
import com.plantarena.tournaments.application.port.out.ParticipantDirectoryGateway;
import com.plantarena.tournaments.application.port.out.PlantDirectoryGateway;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.TournamentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Приглашения (раздел 7): пригласить/отозвать (организатор, до дедлайна),
 * принять с растением (адресат, до дедлайна; резерв в той же tx — ADR-010),
 * отказаться. Принятие: APPROVED-растение → READY сразу, идущая модерация →
 * ACCEPTED_PENDING_MODERATION (не допуск к голосованию).
 */
@Service
public class InvitationService implements InviteUserUseCase, RevokeInvitationUseCase,
        AcceptInvitationUseCase, DeclineInvitationUseCase, ListInvitationsUseCase {

    private final TournamentRepository tournaments;
    private final InvitationRepository invitations;
    private final PlantEligibilityGateway eligibility;
    private final PlantDirectoryGateway plantDirectory;
    private final ParticipantDirectoryGateway participants;
    private final TournamentsAccessPolicy accessPolicy;
    private final IntegrationEventPublisher eventPublisher;
    private final Clock clock;

    public InvitationService(TournamentRepository tournaments,
                             InvitationRepository invitations,
                             PlantEligibilityGateway eligibility,
                             PlantDirectoryGateway plantDirectory,
                             ParticipantDirectoryGateway participants,
                             TournamentsAccessPolicy accessPolicy,
                             IntegrationEventPublisher eventPublisher, Clock clock) {
        this.tournaments = tournaments;
        this.invitations = invitations;
        this.eligibility = eligibility;
        this.plantDirectory = plantDirectory;
        this.participants = participants;
        this.accessPolicy = accessPolicy;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public InvitationData invite(CurrentActor actor, UUID tournamentId, UUID userId) {
        accessPolicy.requireIdentified(actor);
        Tournament tournament = findTournament(tournamentId);
        accessPolicy.requireOrganizer(actor, tournament);
        if (!tournament.canInvite(clock.instant())) {
            throw new RegistrationClosedException(
                "Приглашать можно в DRAFT/REGISTRATION_OPEN до дедлайна");
        }
        if (!participants.isKnownUser(userId)) {
            throw new UnknownUserException("Неизвестный пользователь: " + userId);
        }
        if (invitations.findByTournamentIdAndUserId(tournamentId, userId).isPresent()) {
            throw new DuplicateInvitationException(
                "Пользователь уже приглашён в турнир: " + userId);
        }
        Invitation invitation = invitations.save(
            Invitation.invite(tournamentId, userId, actor.userId(), clock.instant()));
        publishInvitationCreated(invitation);
        return TournamentAssembler.toData(invitation);
    }

    @Override
    @Transactional
    public void revoke(CurrentActor actor, UUID tournamentId, UUID invitationId) {
        accessPolicy.requireIdentified(actor);
        Tournament tournament = findTournament(tournamentId);
        accessPolicy.requireOrganizer(actor, tournament);
        if (!tournament.canInvite(clock.instant())) {
            throw new RegistrationClosedException("Отзывать приглашения можно до дедлайна");
        }
        Invitation invitation = findInvitation(invitationId);
        if (!invitation.tournamentId().equals(tournamentId)) {
            throw new InvitationNotFoundException("Приглашение не найдено: " + invitationId);
        }
        if (invitation.status() != InvitationStatus.INVITED) {
            throw new TournamentStateConflictException(
                "Отзывать можно только не принятые приглашения, статус: " + invitation.status());
        }
        invitation.revoke(clock.instant());
        invitations.save(invitation);
    }

    @Override
    @Transactional
    public InvitationData accept(CurrentActor actor, UUID invitationId, UUID plantId) {
        accessPolicy.requireIdentified(actor);
        Invitation invitation = findInvitation(invitationId);
        accessPolicy.requireAddressee(actor, invitation);
        Tournament tournament = findTournament(invitation.tournamentId());
        if (invitation.status() == InvitationStatus.ACCEPTED_PENDING_MODERATION
                || invitation.status() == InvitationStatus.READY) {
            if (plantId != null && plantId.equals(invitation.submittedPlantId())) {
                return TournamentAssembler.toData(invitation); // идемпотентный повтор
            }
            throw new TournamentStateConflictException(
                "Приглашение уже принято; сначала откажитесь, затем подайте другое растение");
        }
        if (invitation.status() != InvitationStatus.INVITED) {
            throw new TournamentStateConflictException(
                "Приглашение уже закрыто: " + invitation.status());
        }
        if (!tournament.isAcceptingNow(clock.instant())) {
            throw new RegistrationClosedException(
                "Принимать приглашения можно только в REGISTRATION_OPEN до дедлайна");
        }
        PlantDirectoryGateway.PlantSnapshot plant = plantDirectory.findById(plantId)
            .orElseThrow(() -> new InvitedPlantNotFoundException("Растение не найдено: " + plantId));
        UUID submissionKey = UUID.randomUUID(); // свежий ключ на попытку (дизайн, решение 3)
        UUID reservationId = eligibility.reserve(actor.userId(), plantId, submissionKey);
        invitation.accept(plantId, reservationId, submissionKey, plant.approved(),
            clock.instant());
        return TournamentAssembler.toData(invitations.save(invitation));
    }

    @Override
    @Transactional
    public InvitationData decline(CurrentActor actor, UUID invitationId) {
        accessPolicy.requireIdentified(actor);
        Invitation invitation = findInvitation(invitationId);
        accessPolicy.requireAddressee(actor, invitation);
        Tournament tournament = findTournament(invitation.tournamentId());
        if (tournament.status() != TournamentStatus.DRAFT
                && tournament.status() != TournamentStatus.REGISTRATION_OPEN) {
            throw new TournamentStateConflictException("Отказ возможен только до старта");
        }
        if (invitation.status() == InvitationStatus.DECLINED) {
            return TournamentAssembler.toData(invitation); // идемпотентно
        }
        if (invitation.status() == InvitationStatus.REVOKED
                || invitation.status() == InvitationStatus.EXPIRED) {
            throw new TournamentStateConflictException(
                "Приглашение уже закрыто: " + invitation.status());
        }
        if (invitation.reservationId() != null) {
            eligibility.release(invitation.reservationId());
        }
        invitation.decline(clock.instant());
        return TournamentAssembler.toData(invitations.save(invitation));
    }

    @Override
    @Transactional(readOnly = true)
    public InvitationListResult list(CurrentActor actor, UUID tournamentId, int page, int size) {
        accessPolicy.requireIdentified(actor);
        Tournament tournament = findTournament(tournamentId);
        accessPolicy.requireOrganizer(actor, tournament);
        List<InvitationData> items = invitations
            .findByTournamentId(tournamentId, page * size, size).stream()
            .map(TournamentAssembler::toData)
            .toList();
        return new InvitationListResult(items, invitations.countByTournamentId(tournamentId));
    }

    @Override
    @Transactional(readOnly = true)
    public InvitationListResult listMine(CurrentActor actor, int page, int size) {
        accessPolicy.requireIdentified(actor);
        List<InvitationData> items =
            invitations.findByUserId(actor.userId(), page * size, size).stream()
                .map(TournamentAssembler::toData)
                .toList();
        return new InvitationListResult(items, invitations.countByUserId(actor.userId()));
    }

    private void publishInvitationCreated(Invitation invitation) {
        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new InvitationCreatedEvent(eventId, InvitationCreatedEvent.TYPE,
            InvitationCreatedEvent.SCHEMA_VERSION, invitation.id(), invitation.version(),
            clock.instant(), eventId, new InvitationCreatedEvent.Payload(invitation.id(),
                invitation.tournamentId(), invitation.userId())));
    }

    private Tournament findTournament(UUID tournamentId) {
        return tournaments.findById(tournamentId)
            .orElseThrow(() -> new TournamentNotFoundException("Турнир не найден: " + tournamentId));
    }

    private Invitation findInvitation(UUID invitationId) {
        return invitations.findById(invitationId)
            .orElseThrow(() -> new InvitationNotFoundException(
                "Приглашение не найдено: " + invitationId));
    }
}
```

- [ ] **Step 7: Реализация PlantModerationReactionService**

`src/main/java/com/plantarena/tournaments/application/PlantModerationReactionService.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.tournaments.application.port.in.OnPlantModerationDecidedUseCase;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реакция на PlantModerationDecided (раздел 4.3, 7): вызывается слушателем
 * tournaments.adapter.in.events синхронно в tx применения решения модерации
 * (обязательное последствие, раздел 10.3; ADR-010). APPROVED → READY только
 * при REGISTRATION_OPEN, now &lt; deadline и действующем резерве
 * (confirm); REJECTED → возврат в INVITED с освобождением резерва и
 * сохранением истории подачи. Терминальные статусы и заявки отменённого
 * турнира не меняются.
 */
@Service
public class PlantModerationReactionService implements OnPlantModerationDecidedUseCase {

    private final TournamentRepository tournaments;
    private final InvitationRepository invitations;
    private final PlantEligibilityGateway eligibility;
    private final Clock clock;

    public PlantModerationReactionService(TournamentRepository tournaments,
                                          InvitationRepository invitations,
                                          PlantEligibilityGateway eligibility, Clock clock) {
        this.tournaments = tournaments;
        this.invitations = invitations;
        this.eligibility = eligibility;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void onPlantModerationDecided(UUID plantId, String decision) {
        invitations.findBySubmittedPlantIdAndStatus(plantId,
                InvitationStatus.ACCEPTED_PENDING_MODERATION)
            .ifPresent(invitation -> apply(invitation, plantId, decision));
    }

    private void apply(Invitation invitation, UUID plantId, String decision) {
        Tournament tournament = tournaments.findById(invitation.tournamentId())
            .orElseThrow(() -> new TournamentNotFoundException(
                "Турнир не найден: " + invitation.tournamentId()));
        if ("REJECTED".equals(decision)) {
            if (invitation.reservationId() != null) {
                eligibility.release(invitation.reservationId());
            }
            invitation.rollbackToInvited(clock.instant());
            invitations.save(invitation);
            return;
        }
        if ("APPROVED".equals(decision) && tournament.isAcceptingNow(clock.instant())) {
            // резерв ещё действителен: подтверждение существующим резервом (раздел 7)
            eligibility.confirm(invitation.userId(), plantId, invitation.reservationId());
            invitation.markReady(clock.instant());
            invitations.save(invitation);
        }
        // APPROVED после дедлайна/в отменённом турнире — заявка не меняется
    }
}
```

- [ ] **Step 8: Реализация StartTournamentService**

`src/main/java/com/plantarena/tournaments/application/StartTournamentService.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.event.IntegrationEventPublisher;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import com.plantarena.tournaments.api.event.TournamentStartedEvent;
import com.plantarena.tournaments.application.port.in.StartTournamentUseCase;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.TournamentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Один use case старта для ручки и scheduler'а (раздел 7, дизайн итерации 5):
 * start — ручка организатора (после дедлайна, конфликт состояний — 409);
 * startDue — due-турниры из БД, каждый в отдельной короткой tx
 * (TransactionTemplate): один сбой не блокирует остальные. READY ≥
 * minParticipants → RUNNING + TournamentEntry + EXPIRED не-READY +
 * TournamentStarted; иначе CANCELLED (INSUFFICIENT_PARTICIPANTS) с
 * освобождением резервов. Растения не погибают (раздел 7).
 */
@Service
public class StartTournamentService implements StartTournamentUseCase {

    private static final Logger log = LoggerFactory.getLogger(StartTournamentService.class);

    private final TournamentRepository tournaments;
    private final InvitationRepository invitations;
    private final TournamentEntryRepository entries;
    private final PlantEligibilityGateway eligibility;
    private final TournamentsAccessPolicy accessPolicy;
    private final IntegrationEventPublisher eventPublisher;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public StartTournamentService(TournamentRepository tournaments,
                                  InvitationRepository invitations,
                                  TournamentEntryRepository entries,
                                  PlantEligibilityGateway eligibility,
                                  TournamentsAccessPolicy accessPolicy,
                                  IntegrationEventPublisher eventPublisher, Clock clock,
                                  TransactionTemplate transactionTemplate) {
        this.tournaments = tournaments;
        this.invitations = invitations;
        this.entries = entries;
        this.eligibility = eligibility;
        this.accessPolicy = accessPolicy;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    @Transactional
    public TournamentData start(CurrentActor actor, UUID tournamentId) {
        accessPolicy.requireIdentified(actor);
        Tournament tournament = find(tournamentId);
        accessPolicy.requireOrganizer(actor, tournament);
        if (tournament.status() != TournamentStatus.REGISTRATION_OPEN) {
            throw new TournamentStateConflictException(
                "Старт возможен только из REGISTRATION_OPEN, текущий статус: "
                    + tournament.status());
        }
        if (clock.instant().isBefore(tournament.registrationDeadline())) {
            throw new TournamentStateConflictException("Старт возможен только после дедлайна");
        }
        return doStart(tournament);
    }

    @Override
    public int startDue(Instant now, int limit) {
        List<UUID> due = tournaments.findDueForStart(now, limit).stream()
            .map(Tournament::id)
            .toList();
        int processed = 0;
        for (UUID tournamentId : due) {
            try {
                transactionTemplate.executeWithoutResult(
                    status -> startDueOne(tournamentId));
                processed++;
            } catch (RuntimeException e) {
                // один битый турнир не блокирует остальные; повтор — следующий poll
                log.warn("Старт турнира {} не удался, будет повторён: {}", tournamentId,
                    e.getMessage());
            }
        }
        return processed;
    }

    private void startDueOne(UUID tournamentId) {
        Tournament tournament = find(tournamentId);
        if (tournament.status() != TournamentStatus.REGISTRATION_OPEN) {
            return; // уже обработан (идемпотентность повторного poll'а)
        }
        doStart(tournament);
    }

    private TournamentData doStart(Tournament tournament) {
        Instant now = clock.instant();
        List<Invitation> ready =
            invitations.findByTournamentIdAndStatus(tournament.id(), InvitationStatus.READY);
        if (ready.size() < tournament.minParticipants()) {
            tournament.cancelForInsufficientParticipants(now);
            tournaments.save(tournament);
            releaseAcceptedReservations(tournament.id());
            return TournamentAssembler.toData(tournament);
        }
        for (Invitation invitation : ready) {
            // допуск к старту: только APPROVED и действующий резерв (раздел 6)
            eligibility.confirm(invitation.userId(), invitation.submittedPlantId(),
                invitation.reservationId());
        }
        tournament.start(now, ready.size());
        tournaments.save(tournament);
        for (Invitation invitation : ready) {
            entries.save(TournamentEntry.admit(tournament.id(), invitation.userId(),
                invitation.submittedPlantId(), invitation.reservationId(), now));
        }
        expireNotReady(tournament.id(), now);
        publishStarted(tournament);
        return TournamentAssembler.toData(tournament);
    }

    private void expireNotReady(UUID tournamentId, Instant now) {
        for (InvitationStatus status : List.of(InvitationStatus.INVITED,
            InvitationStatus.ACCEPTED_PENDING_MODERATION)) {
            for (Invitation invitation
                    : invitations.findByTournamentIdAndStatus(tournamentId, status)) {
                if (invitation.reservationId() != null) {
                    eligibility.release(invitation.reservationId());
                }
                invitation.expire(now);
                invitations.save(invitation);
            }
        }
    }

    private void releaseAcceptedReservations(UUID tournamentId) {
        for (InvitationStatus status : List.of(InvitationStatus.ACCEPTED_PENDING_MODERATION,
            InvitationStatus.READY)) {
            for (Invitation invitation
                    : invitations.findByTournamentIdAndStatus(tournamentId, status)) {
                if (invitation.reservationId() != null) {
                    eligibility.release(invitation.reservationId());
                }
            }
        }
    }

    private void publishStarted(Tournament tournament) {
        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new TournamentStartedEvent(eventId, TournamentStartedEvent.TYPE,
            TournamentStartedEvent.SCHEMA_VERSION, tournament.id(), tournament.version(),
            clock.instant(), eventId, new TournamentStartedEvent.Payload(tournament.id(),
                tournament.creatorId())));
    }

    private Tournament find(UUID tournamentId) {
        return tournaments.findById(tournamentId)
            .orElseThrow(() -> new TournamentNotFoundException("Турнир не найден: " + tournamentId));
    }
}
```

- [ ] **Step 9: Запустить — зелёный и Commit**

```bash
./mvnw -q test -Dtest='InvitationServiceTest,PlantModerationReactionServiceTest,StartTournamentServiceTest'
```

Ожидание: PASS (14 тестов). Затем `./mvnw -q test` — весь unit-набор зелёный.

```bash
git add src/main/java/com/plantarena/tournaments src/test/java/com/plantarena/tournaments/application
git commit -m "feat(tournaments): application — приглашения, реакция на решение модерации, старт по дедлайну"
```

---

### Task 6: Хранилище — миграция V2, JPA-адаптеры, контрактные тесты

**Files:**
- Create: `src/main/resources/db/migration/tournaments/V2__tournaments.sql`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/{TournamentJpaEntity, TournamentJpaRepository, JpaTournamentRepository, InvitationJpaEntity, InvitationJpaRepository, JpaInvitationRepository, TournamentEntryJpaEntity, TournamentEntryJpaRepository, JpaTournamentEntryRepository, TagJpaEntity, TagJpaRepository, JpaTagRepository}.java`
- Test: `src/test/java/com/plantarena/tournaments/{TournamentRepositoryContractTest, InvitationRepositoryContractTest, TournamentEntryRepositoryContractTest, TagRepositoryContractTest}.java` (абстрактные)
- Test: `src/test/java/com/plantarena/tournaments/application/support/{InMemoryTournamentRepositoryContractTest, InMemoryInvitationRepositoryContractTest, InMemoryTournamentEntryRepositoryContractTest, InMemoryTagRepositoryContractTest}.java`
- Test: `src/test/java/com/plantarena/tournaments/adapter/out/persistence/{JpaTournamentRepositoryContractIT, JpaInvitationRepositoryContractIT, JpaTournamentEntryRepositoryContractIT, JpaTagRepositoryContractIT}.java`

**Interfaces:**
- Consumes: домен Tasks 2–3, `SchemaMigrationConfig`/`PostgresSupport` (существуют), паттерн контрактных тестов `PlantReservationRepositoryContractTest` + `JpaPlantReservationRepositoryContractIT` (урок итерации 2: `@Transactional` на базовом классе).
- Produces (для Task 7): бины репозиториев `Jpa*Tournament*Repository`/`JpaInvitationRepository`/`JpaTagRepository` (Spring собирает application-сервисы Task 4–5 автоматически).

- [ ] **Step 1: Миграция V2**

`src/main/resources/db/migration/tournaments/V2__tournaments.sql`:

```sql
-- Контекст tournaments: закрытые турниры, приглашения, участия, теги
-- (разделы 7, 11). Enum → VARCHAR + CHECK; маппинг явный (без EnumType.STRING).
-- version (optimistic locking) у конкурентных tournament/invitation.
-- Связи внутри схемы-владельца: FK invitation/entry → tournament,
-- join table tournament_tag (PK по двум FK, раздел 11).
CREATE TABLE tournament (
    id                       UUID PRIMARY KEY,
    creator_id               UUID          NOT NULL,
    name                     VARCHAR(100)  NOT NULL,
    description              VARCHAR(1000),
    type                     VARCHAR(10)   NOT NULL CHECK (type IN ('PRIVATE')),
    status                   VARCHAR(20)   NOT NULL CHECK (status IN ('DRAFT', 'REGISTRATION_OPEN', 'RUNNING', 'FINISHED', 'CANCELLED')),
    algorithm                VARCHAR(20)   NOT NULL CHECK (algorithm IN ('ROUND_ELIMINATION')),
    registration_deadline    TIMESTAMPTZ   NOT NULL,
    round_duration_seconds   BIGINT        NOT NULL CHECK (round_duration_seconds > 0),
    elimination_fraction     NUMERIC(5, 4) NOT NULL CHECK (elimination_fraction > 0 AND elimination_fraction < 1),
    min_participants         INTEGER       NOT NULL CHECK (min_participants >= 2),
    cancel_reason            VARCHAR(30)   CHECK (cancel_reason IS NULL OR cancel_reason IN ('INSUFFICIENT_PARTICIPANTS')),
    created_at               TIMESTAMPTZ   NOT NULL,
    version                  BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX tournament_status_deadline_idx ON tournament (status, registration_deadline);
CREATE INDEX tournament_creator_idx ON tournament (creator_id);

CREATE TABLE invitation (
    id                 UUID PRIMARY KEY,
    tournament_id      UUID         NOT NULL REFERENCES tournament (id),
    user_id            UUID         NOT NULL,
    invited_by         UUID         NOT NULL,
    status             VARCHAR(30)  NOT NULL CHECK (status IN ('INVITED', 'ACCEPTED_PENDING_MODERATION', 'READY', 'DECLINED', 'REVOKED', 'EXPIRED')),
    invited_at         TIMESTAMPTZ  NOT NULL,
    responded_at       TIMESTAMPTZ,
    submitted_plant_id UUID,
    reservation_id     UUID,
    submission_key     UUID,
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT invitation_pair_uidx UNIQUE (tournament_id, user_id)
);
CREATE INDEX invitation_user_status_idx ON invitation (user_id, status);
CREATE INDEX invitation_tournament_status_idx ON invitation (tournament_id, status);
CREATE INDEX invitation_plant_status_idx ON invitation (submitted_plant_id, status);

CREATE TABLE tournament_entry (
    id             UUID PRIMARY KEY,
    tournament_id  UUID         NOT NULL REFERENCES tournament (id),
    user_id        UUID         NOT NULL,
    plant_id       UUID         NOT NULL,
    reservation_id UUID         NOT NULL,
    status         VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'ELIMINATED', 'WINNER')),
    joined_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT tournament_entry_pair_uidx UNIQUE (tournament_id, user_id)
);

CREATE TABLE tag (
    id         UUID PRIMARY KEY,
    name       VARCHAR(50) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE tournament_tag (
    tournament_id UUID NOT NULL REFERENCES tournament (id),
    tag_id        UUID NOT NULL REFERENCES tag (id),
    PRIMARY KEY (tournament_id, tag_id)
);
```

- [ ] **Step 2: Абстрактные контрактные тесты (красный)**

`src/test/java/com/plantarena/tournaments/TournamentRepositoryContractTest.java`:

```java
package com.plantarena.tournaments;

import com.plantarena.tournaments.domain.Tag;
import com.plantarena.tournaments.domain.TagRepository;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.TournamentStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт TournamentRepository (раздел 14.2): фейк и JPA + PostgreSQL честны
 * к одной семантике search/count/findDueForStart/delete.
 * @Transactional обязателен на базовом классе (урок итерации 2).
 */
@DisplayName("Контракт TournamentRepository")
@Transactional
public abstract class TournamentRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    protected abstract TournamentRepository repository();

    protected abstract TagRepository tags();

    private Tournament draft(UUID creatorId, Instant createdAt, UUID tagId) {
        return Tournament.restore(UUID.randomUUID(), creatorId, "Турнир " + createdAt,
            "Описание", com.plantarena.tournaments.domain.TournamentType.PRIVATE,
            TournamentStatus.DRAFT,
            com.plantarena.tournaments.domain.EliminationAlgorithmKind.ROUND_ELIMINATION,
            NOW.plusSeconds(86400), Duration.ofHours(1), 0.5, 2, null,
            tagId == null ? Set.of() : Set.of(tagId), createdAt, 0);
    }

    @Test
    @DisplayName("save/findById: полный roundtrip с тегами")
    void roundtrip() {
        UUID tagId = tags().save(Tag.create("Тег контракта", NOW)).id();
        Tournament tournament = draft(UUID.randomUUID(), NOW, tagId);
        repository().save(tournament);

        Tournament loaded = repository().findById(tournament.id()).orElseThrow();
        assertThat(loaded.name()).isEqualTo(tournament.name());
        assertThat(loaded.status()).isEqualTo(TournamentStatus.DRAFT);
        assertThat(loaded.registrationDeadline()).isEqualTo(tournament.registrationDeadline());
        assertThat(loaded.roundDuration()).isEqualTo(Duration.ofHours(1));
        assertThat(loaded.eliminationFraction()).isEqualTo(0.5);
        assertThat(loaded.minParticipants()).isEqualTo(2);
        assertThat(loaded.tagIds()).containsExactly(tagId);
        assertThat(loaded.version()).isEqualTo(tournament.version());
    }

    @Test
    @DisplayName("delete удаляет черновик")
    void delete_черновик() {
        Tournament tournament = draft(UUID.randomUUID(), NOW, null);
        repository().save(tournament);
        repository().delete(tournament.id());
        assertThat(repository().findById(tournament.id())).isEmpty();
    }

    @Test
    @DisplayName("search/count: админ видит все, фильтры статуса и тега, пагинация")
    void search_фильтры() {
        UUID tagId = tags().save(Tag.create("Фильтр", NOW)).id();
        Tournament first = draft(UUID.randomUUID(), NOW, tagId);
        Tournament second = draft(UUID.randomUUID(), NOW.plusSeconds(1), null);
        Tournament opened = draft(UUID.randomUUID(), NOW.plusSeconds(2), null);
        opened.openRegistration(NOW);
        repository().save(first);
        repository().save(second);
        repository().save(opened);

        var adminAll = new TournamentRepository.TournamentFilter(null, true, null, null, 0, 50);
        assertThat(repository().search(adminAll)).hasSize(3);
        assertThat(repository().count(adminAll)).isEqualTo(3);

        var byStatus = new TournamentRepository.TournamentFilter(null, true,
            TournamentStatus.REGISTRATION_OPEN, null, 0, 50);
        assertThat(repository().search(byStatus)).extracting(Tournament::id)
            .containsExactly(opened.id());

        var byTag = new TournamentRepository.TournamentFilter(null, true, null, tagId, 0, 50);
        assertThat(repository().search(byTag)).extracting(Tournament::id)
            .containsExactly(first.id());

        var page = new TournamentRepository.TournamentFilter(null, true, null, null, 1, 2);
        assertThat(repository().search(page)).hasSize(1); // createdAt desc + id
    }

    @Test
    @DisplayName("findDueForStart: только REGISTRATION_OPEN с наступившим дедлайном")
    void find_due_for_start() {
        Tournament due = draft(UUID.randomUUID(), NOW, null);
        due.openRegistration(NOW);
        due.start(NOW.plusSeconds(86400), 2); // RUNNING — не due
        Tournament notDue = draft(UUID.randomUUID(), NOW, null);
        notDue.openRegistration(NOW);
        Tournament stillDraft = draft(UUID.randomUUID(), NOW, null);
        repository().save(due);
        repository().save(notDue);
        repository().save(stillDraft);

        assertThat(repository().findDueForStart(NOW.plusSeconds(86400), 10))
            .extracting(Tournament::id)
            .containsExactly(notDue.id());
    }
}
```

`src/test/java/com/plantarena/tournaments/InvitationRepositoryContractTest.java`:

```java
package com.plantarena.tournaments;

import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Контракт InvitationRepository: уникальность пары (tournamentId, userId)
 * обеспечивает и фейк, и UNIQUE-индекс PostgreSQL (раздел 11).
 */
@DisplayName("Контракт InvitationRepository")
@Transactional
public abstract class InvitationRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    protected abstract InvitationRepository repository();

    protected abstract TournamentRepository tournaments();

    private UUID newTournamentId() {
        Tournament tournament = Tournament.createDraft(UUID.randomUUID(), "Т", null,
            NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, Set.of(), NOW);
        return tournaments().save(tournament).id();
    }

    @Test
    @DisplayName("save/findById: roundtrip всех полей, включая заявку")
    void roundtrip() {
        UUID tournamentId = newTournamentId();
        UUID userId = UUID.randomUUID();
        Invitation invitation = Invitation.invite(tournamentId, userId,
            UUID.randomUUID(), NOW);
        invitation.accept(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false, NOW);
        repository().save(invitation);

        Invitation loaded = repository().findById(invitation.id()).orElseThrow();
        assertThat(loaded.tournamentId()).isEqualTo(tournamentId);
        assertThat(loaded.userId()).isEqualTo(userId);
        assertThat(loaded.status()).isEqualTo(InvitationStatus.ACCEPTED_PENDING_MODERATION);
        assertThat(loaded.submittedPlantId()).isEqualTo(invitation.submittedPlantId());
        assertThat(loaded.reservationId()).isEqualTo(invitation.reservationId());
        assertThat(loaded.submissionKey()).isEqualTo(invitation.submissionKey());
        assertThat(loaded.respondedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("уникальность пары (tournamentId, userId) — как БД")
    void уникальность_пары() {
        UUID tournamentId = newTournamentId();
        UUID userId = UUID.randomUUID();
        repository().save(Invitation.invite(tournamentId, userId, UUID.randomUUID(), NOW));

        assertThatThrownBy(() -> repository().save(
            Invitation.invite(tournamentId, userId, UUID.randomUUID(), NOW)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("поиски: по паре, по растению и статусу, по турниру/статусу, exists")
    void поиски() {
        UUID tournamentId = newTournamentId();
        UUID userId = UUID.randomUUID();
        Invitation invitation = repository().save(
            Invitation.invite(tournamentId, userId, UUID.randomUUID(), NOW));
        Invitation other = repository().save(Invitation.invite(tournamentId,
            UUID.randomUUID(), UUID.randomUUID(), NOW));
        Invitation inOtherTournament = repository().save(Invitation.invite(newTournamentId(),
            UUID.randomUUID(), UUID.randomUUID(), NOW));

        assertThat(repository().findByTournamentIdAndUserId(tournamentId, userId))
            .contains(invitation);
        assertThat(repository().findByTournamentIdAndUserId(tournamentId, UUID.randomUUID()))
            .isEmpty();

        UUID plantId = UUID.randomUUID();
        Invitation accepted = Invitation.invite(inOtherTournament.tournamentId(),
            UUID.randomUUID(), UUID.randomUUID(), NOW);
        accepted.accept(plantId, UUID.randomUUID(), UUID.randomUUID(), false, NOW);
        repository().save(accepted);
        assertThat(repository().findBySubmittedPlantIdAndStatus(plantId,
            InvitationStatus.ACCEPTED_PENDING_MODERATION)).contains(accepted);
        assertThat(repository().findBySubmittedPlantIdAndStatus(plantId,
            InvitationStatus.INVITED)).isEmpty();

        assertThat(repository().findByTournamentIdAndStatus(tournamentId,
            InvitationStatus.INVITED)).extracting(Invitation::id)
            .containsExactlyInAnyOrder(invitation.id(), other.id());
        assertThat(repository().existsByTournamentId(tournamentId)).isTrue();
        assertThat(repository().existsByTournamentId(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("списки с пагинацией и count: по турниру и по пользователю")
    void списки() {
        UUID tournamentId = newTournamentId();
        UUID userId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            repository().save(Invitation.invite(tournamentId, UUID.randomUUID(),
                UUID.randomUUID(), NOW));
        }
        repository().save(Invitation.invite(newTournamentId(), userId,
            UUID.randomUUID(), NOW));

        assertThat(repository().findByTournamentId(tournamentId, 0, 2)).hasSize(2);
        assertThat(repository().countByTournamentId(tournamentId)).isEqualTo(3);
        assertThat(repository().findByUserId(userId, 0, 20)).hasSize(1);
        assertThat(repository().countByUserId(userId)).isEqualTo(1);
    }
}
```

`src/test/java/com/plantarena/tournaments/TournamentEntryRepositoryContractTest.java`:

```java
package com.plantarena.tournaments;

import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Контракт TournamentEntryRepository: уникальность (tournamentId, userId). */
@DisplayName("Контракт TournamentEntryRepository")
@Transactional
public abstract class TournamentEntryRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    protected abstract TournamentEntryRepository repository();

    protected abstract TournamentRepository tournaments();

    private UUID newTournamentId() {
        Tournament tournament = Tournament.createDraft(UUID.randomUUID(), "Т", null,
            NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, Set.of(), NOW);
        return tournaments().save(tournament).id();
    }

    @Test
    @DisplayName("save + списки + exists")
    void save_и_поиски() {
        UUID tournamentId = newTournamentId();
        UUID userId = UUID.randomUUID();
        TournamentEntry entry = TournamentEntry.admit(tournamentId, userId,
            UUID.randomUUID(), UUID.randomUUID(), NOW);
        repository().save(entry);
        repository().save(TournamentEntry.admit(tournamentId, UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), NOW));

        assertThat(repository().findByTournamentId(tournamentId, 0, 1)).hasSize(1);
        assertThat(repository().countByTournamentId(tournamentId)).isEqualTo(2);
        assertThat(repository().existsByTournamentIdAndUserId(tournamentId, userId)).isTrue();
        assertThat(repository().existsByTournamentIdAndUserId(tournamentId,
            UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("уникальность пары (tournamentId, userId) — как БД")
    void уникальность_пары() {
        UUID tournamentId = newTournamentId();
        UUID userId = UUID.randomUUID();
        repository().save(TournamentEntry.admit(tournamentId, userId, UUID.randomUUID(),
            UUID.randomUUID(), NOW));

        assertThatThrownBy(() -> repository().save(
            TournamentEntry.admit(tournamentId, userId, UUID.randomUUID(),
                UUID.randomUUID(), NOW)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

`src/test/java/com/plantarena/tournaments/TagRepositoryContractTest.java`:

```java
package com.plantarena.tournaments;

import com.plantarena.tournaments.domain.Tag;
import com.plantarena.tournaments.domain.TagRepository;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** Контракт TagRepository: уникальность имени, isUsedByTournament, пагинация. */
@DisplayName("Контракт TagRepository")
@Transactional
public abstract class TagRepositoryContractTest {

    protected static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    protected abstract TagRepository repository();

    protected abstract TournamentRepository tournaments();

    @Test
    @DisplayName("save/findByName/findAll/delete и использование турниром")
    void crud_и_использование() {
        Tag tag = repository().save(Tag.create("Контрактный", NOW));
        repository().save(Tag.create("Второй", NOW));

        assertThat(repository().findById(tag.id())).contains(tag);
        assertThat(repository().findByName("Контрактный")).contains(tag);
        assertThat(repository().findByName("Нет такого")).isEmpty();
        assertThat(repository().findAll(0, 1)).hasSize(1);
        assertThat(repository().count()).isEqualTo(2);
        assertThat(repository().isUsedByTournament(tag.id())).isFalse();

        Tournament tournament = Tournament.createDraft(UUID.randomUUID(), "Т", null,
            NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, Set.of(tag.id()), NOW);
        tournaments().save(tournament);
        assertThat(repository().isUsedByTournament(tag.id())).isTrue();

        repository().delete(tag.id());
        assertThat(repository().findById(tag.id())).isEmpty();
    }
}
```

- [ ] **Step 3: Запустить in-memory варианты — красный**

`src/test/java/com/plantarena/tournaments/application/support/InMemoryTournamentRepositoryContractTest.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.TournamentRepositoryContractTest;
import com.plantarena.tournaments.domain.TagRepository;
import com.plantarena.tournaments.domain.TournamentRepository;

/** Фейк честен контракту TournamentRepository (раздел 14.2). */
class InMemoryTournamentRepositoryContractTest extends TournamentRepositoryContractTest {

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryTagRepository tags = new InMemoryTagRepository(tournaments);

    @Override
    protected TournamentRepository repository() {
        return tournaments;
    }

    @Override
    protected TagRepository tags() {
        return tags;
    }
}
```

`src/test/java/com/plantarena/tournaments/application/support/InMemoryInvitationRepositoryContractTest.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.InvitationRepositoryContractTest;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.TournamentRepository;

/** Фейк честен контракту InvitationRepository (UNIQUE-пара — как БД). */
class InMemoryInvitationRepositoryContractTest extends InvitationRepositoryContractTest {

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations =
        new InMemoryInvitationRepository(tournaments);

    @Override
    protected InvitationRepository repository() {
        return invitations;
    }

    @Override
    protected TournamentRepository tournaments() {
        return tournaments;
    }
}
```

`src/test/java/com/plantarena/tournaments/application/support/InMemoryTournamentEntryRepositoryContractTest.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.TournamentEntryRepositoryContractTest;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.TournamentRepository;

/** Фейк честен контракту TournamentEntryRepository. */
class InMemoryTournamentEntryRepositoryContractTest
        extends TournamentEntryRepositoryContractTest {

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryTournamentEntryRepository entries =
        new InMemoryTournamentEntryRepository(tournaments);

    @Override
    protected TournamentEntryRepository repository() {
        return entries;
    }

    @Override
    protected TournamentRepository tournaments() {
        return tournaments;
    }
}
```

`src/test/java/com/plantarena/tournaments/application/support/InMemoryTagRepositoryContractTest.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.TagRepositoryContractTest;
import com.plantarena.tournaments.domain.TagRepository;
import com.plantarena.tournaments.domain.TournamentRepository;

/** Фейк честен контракту TagRepository. */
class InMemoryTagRepositoryContractTest extends TagRepositoryContractTest {

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryTagRepository tags = new InMemoryTagRepository(tournaments);

    @Override
    protected TagRepository repository() {
        return tags;
    }

    @Override
    protected TournamentRepository tournaments() {
        return tournaments;
    }
}
```

```bash
./mvnw -q test -Dtest='InMemoryTournamentRepositoryContractTest,InMemoryInvitationRepositoryContractTest,InMemoryTournamentEntryRepositoryContractTest,InMemoryTagRepositoryContractTest'
```

Ожидание: PASS (in-memory фейки Tasks 4–5 уже честны; если падают — править фейки, не контракт).

- [ ] **Step 4: JPA-модели и Spring Data**

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/TournamentJpaEntity.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * JPA-модель tournament (раздел 11); маппинг на домен — явный
 * (JpaTournamentRepository). M2M tournament ↔ tag — join table с PK по двум
 * FK (раздел 11). Мутирует — optimistic locking через @Version.
 */
@Entity
@Table(name = "tournament", schema = "tournaments")
public class TournamentJpaEntity {

    @Id
    private UUID id;

    @Column(name = "creator_id", nullable = false)
    private UUID creatorId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "type", nullable = false, length = 10)
    private String type;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "algorithm", nullable = false, length = 20)
    private String algorithm;

    @Column(name = "registration_deadline", nullable = false)
    private Instant registrationDeadline;

    @Column(name = "round_duration_seconds", nullable = false)
    private long roundDurationSeconds;

    @Column(name = "elimination_fraction", nullable = false)
    private double eliminationFraction;

    @Column(name = "min_participants", nullable = false)
    private int minParticipants;

    @Column(name = "cancel_reason", length = 30)
    private String cancelReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "tournament_tag",
        joinColumns = @JoinColumn(name = "tournament_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<TagJpaEntity> tags = new HashSet<>();

    protected TournamentJpaEntity() {
    }

    TournamentJpaEntity(UUID id, UUID creatorId, String name, String type, String algorithm,
                        Instant registrationDeadline, long roundDurationSeconds,
                        double eliminationFraction, int minParticipants, Instant createdAt) {
        this.id = id;
        this.creatorId = creatorId;
        this.name = name;
        this.type = type;
        this.algorithm = algorithm;
        this.registrationDeadline = registrationDeadline;
        this.roundDurationSeconds = roundDurationSeconds;
        this.eliminationFraction = eliminationFraction;
        this.minParticipants = minParticipants;
        this.createdAt = createdAt;
        this.status = "DRAFT";
    }

    /** Мутации домена; id/creator/type/algorithm/created_at неизменяемы. */
    void update(String name, String description, String status,
                Instant registrationDeadline, long roundDurationSeconds,
                double eliminationFraction, int minParticipants, String cancelReason,
                Set<TagJpaEntity> tags) {
        this.name = name;
        this.description = description;
        this.status = status;
        this.registrationDeadline = registrationDeadline;
        this.roundDurationSeconds = roundDurationSeconds;
        this.eliminationFraction = eliminationFraction;
        this.minParticipants = minParticipants;
        this.cancelReason = cancelReason;
        this.tags.clear();
        this.tags.addAll(tags);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public Instant getRegistrationDeadline() {
        return registrationDeadline;
    }

    public long getRoundDurationSeconds() {
        return roundDurationSeconds;
    }

    public double getEliminationFraction() {
        return eliminationFraction;
    }

    public int getMinParticipants() {
        return minParticipants;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }

    public Set<TagJpaEntity> getTags() {
        return tags;
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/TagJpaEntity.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA-модель tag (раздел 11); единственная мутация — rename (без version). */
@Entity
@Table(name = "tag", schema = "tournaments")
public class TagJpaEntity {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false, length = 50, unique = true)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TagJpaEntity() {
    }

    TagJpaEntity(UUID id, String name, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
    }

    void update(String name) {
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/InvitationJpaEntity.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA-модель invitation (раздел 11): @ManyToOne LAZY → tournament (FK внутри
 * схемы-владельца). Мутирует (accept/rollback/decline/revoke/expire) —
 * optimistic locking через @Version.
 */
@Entity
@Table(name = "invitation", schema = "tournaments")
public class InvitationJpaEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tournament_id", nullable = false)
    private TournamentJpaEntity tournament;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "invited_at", nullable = false)
    private Instant invitedAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "submitted_plant_id")
    private UUID submittedPlantId;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "submission_key")
    private UUID submissionKey;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected InvitationJpaEntity() {
    }

    InvitationJpaEntity(UUID id, TournamentJpaEntity tournament, UUID userId, UUID invitedBy,
                        Instant invitedAt) {
        this.id = id;
        this.tournament = tournament;
        this.userId = userId;
        this.invitedBy = invitedBy;
        this.invitedAt = invitedAt;
        this.status = "INVITED";
    }

    /** Мутации домена; tournament/user/invitedBy/invitedAt неизменяемы. */
    void update(String status, Instant respondedAt, UUID submittedPlantId,
                UUID reservationId, UUID submissionKey) {
        this.status = status;
        this.respondedAt = respondedAt;
        this.submittedPlantId = submittedPlantId;
        this.reservationId = reservationId;
        this.submissionKey = submissionKey;
    }

    public UUID getId() {
        return id;
    }

    public TournamentJpaEntity getTournament() {
        return tournament;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public String getStatus() {
        return status;
    }

    public Instant getInvitedAt() {
        return invitedAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public UUID getSubmittedPlantId() {
        return submittedPlantId;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public UUID getSubmissionKey() {
        return submissionKey;
    }

    public long getVersion() {
        return version;
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/TournamentEntryJpaEntity.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA-модель tournament_entry (раздел 11): создаётся при старте, в итерации 5
 * не мутирует (переходы — итерация 6) — без version.
 */
@Entity
@Table(name = "tournament_entry", schema = "tournaments")
public class TournamentEntryJpaEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tournament_id", nullable = false)
    private TournamentJpaEntity tournament;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "plant_id", nullable = false)
    private UUID plantId;

    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;

    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    protected TournamentEntryJpaEntity() {
    }

    TournamentEntryJpaEntity(UUID id, TournamentJpaEntity tournament, UUID userId,
                             UUID plantId, UUID reservationId, Instant joinedAt) {
        this.id = id;
        this.tournament = tournament;
        this.userId = userId;
        this.plantId = plantId;
        this.reservationId = reservationId;
        this.joinedAt = joinedAt;
        this.status = "ACTIVE";
    }

    public UUID getId() {
        return id;
    }

    public TournamentJpaEntity getTournament() {
        return tournament;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getPlantId() {
        return plantId;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public String getStatus() {
        return status;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/TournamentJpaRepository.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data для tournament: доступность (раздел 13) и due-дедлайны (раздел 7). */
public interface TournamentJpaRepository extends JpaRepository<TournamentJpaEntity, UUID> {

    @Query("""
        select t from TournamentJpaEntity t
        where (:admin = true or t.creatorId = :userId
            or exists (select 1 from InvitationJpaEntity i
                where i.tournament = t and i.userId = :userId
                    and i.status in ('INVITED', 'ACCEPTED_PENDING_MODERATION', 'READY'))
            or exists (select 1 from TournamentEntryJpaEntity e
                where e.tournament = t and e.userId = :userId))
        and (:status is null or t.status = :status)
        and (:tagId is null or exists (select 1 from TournamentJpaEntity t2 join t2.tags g
                where t2.id = t.id and g.id = :tagId))
        order by t.createdAt desc, t.id asc
        """)
    List<TournamentJpaEntity> search(@Param("admin") boolean admin,
                                     @Param("userId") UUID userId,
                                     @Param("status") String status,
                                     @Param("tagId") UUID tagId, Pageable pageable);

    @Query("""
        select count(t) from TournamentJpaEntity t
        where (:admin = true or t.creatorId = :userId
            or exists (select 1 from InvitationJpaEntity i
                where i.tournament = t and i.userId = :userId
                    and i.status in ('INVITED', 'ACCEPTED_PENDING_MODERATION', 'READY'))
            or exists (select 1 from TournamentEntryJpaEntity e
                where e.tournament = t and e.userId = :userId))
        and (:status is null or t.status = :status)
        and (:tagId is null or exists (select 1 from TournamentJpaEntity t2 join t2.tags g
                where t2.id = t.id and g.id = :tagId))
        """)
    long searchCount(@Param("admin") boolean admin, @Param("userId") UUID userId,
                     @Param("status") String status, @Param("tagId") UUID tagId);

    @Query("""
        select t from TournamentJpaEntity t
        where t.status = 'REGISTRATION_OPEN' and t.registrationDeadline <= :now
        order by t.registrationDeadline asc, t.id asc
        """)
    List<TournamentJpaEntity> findDueForStart(@Param("now") Instant now, Pageable pageable);
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/InvitationJpaRepository.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data для invitation; статусы — строки (маппинг явный). */
public interface InvitationJpaRepository extends JpaRepository<InvitationJpaEntity, UUID> {

    Optional<InvitationJpaEntity> findByTournamentIdAndUserId(UUID tournamentId, UUID userId);

    Optional<InvitationJpaEntity> findBySubmittedPlantIdAndStatus(UUID submittedPlantId,
                                                                  String status);

    List<InvitationJpaEntity> findByTournamentIdOrderByInvitedAtAscIdAsc(UUID tournamentId,
                                                                         Pageable pageable);

    long countByTournamentId(UUID tournamentId);

    List<InvitationJpaEntity> findByUserIdOrderByInvitedAtDescIdAsc(UUID userId,
                                                                    Pageable pageable);

    long countByUserId(UUID userId);

    List<InvitationJpaEntity> findByTournamentIdAndStatus(UUID tournamentId, String status);

    boolean existsByTournamentId(UUID tournamentId);
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/TournamentEntryJpaRepository.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data для tournament_entry. */
public interface TournamentEntryJpaRepository extends JpaRepository<TournamentEntryJpaEntity,
        UUID> {

    List<TournamentEntryJpaEntity> findByTournamentIdOrderByJoinedAtAscIdAsc(UUID tournamentId,
                                                                             Pageable pageable);

    long countByTournamentId(UUID tournamentId);

    boolean existsByTournamentIdAndUserId(UUID tournamentId, UUID userId);
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/TagJpaRepository.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data для tag; isUsedByTournament — join с tournament_tag (раздел 11). */
public interface TagJpaRepository extends JpaRepository<TagJpaEntity, UUID> {

    Optional<TagJpaEntity> findByName(String name);

    List<TagJpaEntity> findAllByOrderByNameAscIdAsc(Pageable pageable);

    @Query("select count(t) > 0 from TournamentJpaEntity t join t.tags g where g.id = :tagId")
    boolean isUsedByTournament(@Param("tagId") UUID tagId);
}
```

- [ ] **Step 5: JPA-адаптеры портов**

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaTournamentRepository.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.CancelReason;
import com.plantarena.tournaments.domain.EliminationAlgorithmKind;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.TournamentStatus;
import com.plantarena.tournaments.domain.TournamentType;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация порта TournamentRepository на JPA + PostgreSQL (раздел 14.2).
 * find-or-create + update + saveAndFlush (паттерн JpaPlantRepository):
 * конкурентные старт/отмена ловит @Version в БД. Теги синхронизируются через
 * @ManyToMany (раздел 11).
 */
@Repository
@Transactional
public class JpaTournamentRepository implements TournamentRepository {

    private final TournamentJpaRepository tournaments;
    private final TagJpaRepository tags;

    public JpaTournamentRepository(TournamentJpaRepository tournaments, TagJpaRepository tags) {
        this.tournaments = tournaments;
        this.tags = tags;
    }

    @Override
    public Tournament save(Tournament tournament) {
        TournamentJpaEntity entity = tournaments.findById(tournament.id())
            .orElseGet(() -> new TournamentJpaEntity(tournament.id(), tournament.creatorId(),
                tournament.name(), tournament.type().name(), tournament.algorithm().name(),
                tournament.registrationDeadline(), tournament.roundDuration().toSeconds(),
                tournament.eliminationFraction(), tournament.minParticipants(),
                tournament.createdAt()));
        entity.update(tournament.name(), tournament.description(),
            tournament.status().name(), tournament.registrationDeadline(),
            tournament.roundDuration().toSeconds(), tournament.eliminationFraction(),
            tournament.minParticipants(),
            tournament.cancelReason() == null ? null : tournament.cancelReason().name(),
            new HashSet<>(tags.findAllById(tournament.tagIds())));
        return toDomain(tournaments.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tournament> findById(UUID id) {
        return tournaments.findById(id).map(JpaTournamentRepository::toDomain);
    }

    @Override
    public void delete(UUID id) {
        tournaments.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Tournament> search(TournamentFilter filter) {
        return tournaments.search(filter.admin(), filter.userId(),
                filter.status() == null ? null : filter.status().name(), filter.tagId(),
                PageRequest.of(filter.offset() / filter.size(), filter.size()))
            .stream().map(JpaTournamentRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(TournamentFilter filter) {
        return tournaments.searchCount(filter.admin(), filter.userId(),
            filter.status() == null ? null : filter.status().name(), filter.tagId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Tournament> findDueForStart(Instant now, int limit) {
        return tournaments.findDueForStart(now, PageRequest.of(0, limit)).stream()
            .map(JpaTournamentRepository::toDomain).toList();
    }

    private static Tournament toDomain(TournamentJpaEntity entity) {
        Set<UUID> tagIds = new HashSet<>();
        entity.getTags().forEach(tag -> tagIds.add(tag.getId()));
        return Tournament.restore(entity.getId(), entity.getCreatorId(), entity.getName(),
            entity.getDescription(), TournamentType.valueOf(entity.getType()),
            TournamentStatus.valueOf(entity.getStatus()),
            EliminationAlgorithmKind.valueOf(entity.getAlgorithm()),
            entity.getRegistrationDeadline(), Duration.ofSeconds(entity.getRoundDurationSeconds()),
            entity.getEliminationFraction(), entity.getMinParticipants(),
            entity.getCancelReason() == null ? null : CancelReason.valueOf(entity.getCancelReason()),
            tagIds, entity.getCreatedAt(), entity.getVersion());
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaInvitationRepository.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация порта InvitationRepository: find-or-create + update +
 * saveAndFlush; нарушение UNIQUE(tournament_id, user_id) переводится в
 * DataIntegrityViolationException (как в PlantEligibilityService).
 */
@Repository
@Transactional
public class JpaInvitationRepository implements InvitationRepository {

    private final InvitationJpaRepository invitations;
    private final TournamentJpaRepository tournaments;

    public JpaInvitationRepository(InvitationJpaRepository invitations,
                                   TournamentJpaRepository tournaments) {
        this.invitations = invitations;
        this.tournaments = tournaments;
    }

    @Override
    public Invitation save(Invitation invitation) {
        TournamentJpaEntity tournament = tournaments.findById(invitation.tournamentId())
            .orElseThrow(() -> new DataIntegrityViolationException(
                "Турнир приглашения не найден: " + invitation.tournamentId()));
        InvitationJpaEntity entity = invitations.findById(invitation.id())
            .orElseGet(() -> new InvitationJpaEntity(invitation.id(), tournament,
                invitation.userId(), invitation.invitedBy(), invitation.invitedAt()));
        entity.update(invitation.status().name(), invitation.respondedAt(),
            invitation.submittedPlantId(), invitation.reservationId(),
            invitation.submissionKey());
        return toDomain(invitations.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invitation> findById(UUID id) {
        return invitations.findById(id).map(JpaInvitationRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invitation> findByTournamentIdAndUserId(UUID tournamentId, UUID userId) {
        return invitations.findByTournamentIdAndUserId(tournamentId, userId)
            .map(JpaInvitationRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invitation> findBySubmittedPlantIdAndStatus(UUID plantId,
                                                                InvitationStatus status) {
        return invitations.findBySubmittedPlantIdAndStatus(plantId, status.name())
            .map(JpaInvitationRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Invitation> findByTournamentId(UUID tournamentId, int offset, int size) {
        return invitations.findByTournamentIdOrderByInvitedAtAscIdAsc(tournamentId,
                PageRequest.of(offset / size, size))
            .stream().map(JpaInvitationRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByTournamentId(UUID tournamentId) {
        return invitations.countByTournamentId(tournamentId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Invitation> findByUserId(UUID userId, int offset, int size) {
        return invitations.findByUserIdOrderByInvitedAtDescIdAsc(userId,
                PageRequest.of(offset / size, size))
            .stream().map(JpaInvitationRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByUserId(UUID userId) {
        return invitations.countByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Invitation> findByTournamentIdAndStatus(UUID tournamentId,
                                                        InvitationStatus status) {
        return invitations.findByTournamentIdAndStatus(tournamentId, status.name()).stream()
            .map(JpaInvitationRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByTournamentId(UUID tournamentId) {
        return invitations.existsByTournamentId(tournamentId);
    }

    private static Invitation toDomain(InvitationJpaEntity entity) {
        return Invitation.restore(entity.getId(), entity.getTournament().getId(),
            entity.getUserId(), entity.getInvitedBy(),
            InvitationStatus.valueOf(entity.getStatus()), entity.getInvitedAt(),
            entity.getRespondedAt(), entity.getSubmittedPlantId(), entity.getReservationId(),
            entity.getSubmissionKey(), entity.getVersion());
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaTournamentEntryRepository.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Реализация порта TournamentEntryRepository (создание при старте). */
@Repository
@Transactional
public class JpaTournamentEntryRepository implements TournamentEntryRepository {

    private final TournamentEntryJpaRepository entries;
    private final TournamentJpaRepository tournaments;

    public JpaTournamentEntryRepository(TournamentEntryJpaRepository entries,
                                        TournamentJpaRepository tournaments) {
        this.entries = entries;
        this.tournaments = tournaments;
    }

    @Override
    public TournamentEntry save(TournamentEntry entry) {
        TournamentJpaEntity tournament = tournaments.findById(entry.tournamentId())
            .orElseThrow(() -> new DataIntegrityViolationException(
                "Турнир участия не найден: " + entry.tournamentId()));
        TournamentEntryJpaEntity entity = entries.findById(entry.id())
            .orElseGet(() -> new TournamentEntryJpaEntity(entry.id(), tournament,
                entry.userId(), entry.plantId(), entry.reservationId(), entry.joinedAt()));
        return toDomain(entries.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TournamentEntry> findByTournamentId(UUID tournamentId, int offset, int size) {
        return entries.findByTournamentIdOrderByJoinedAtAscIdAsc(tournamentId,
                PageRequest.of(offset / size, size))
            .stream().map(JpaTournamentEntryRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByTournamentId(UUID tournamentId) {
        return entries.countByTournamentId(tournamentId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByTournamentIdAndUserId(UUID tournamentId, UUID userId) {
        return entries.existsByTournamentIdAndUserId(tournamentId, userId);
    }

    private static TournamentEntry toDomain(TournamentEntryJpaEntity entity) {
        return TournamentEntry.restore(entity.getId(), entity.getTournament().getId(),
            entity.getUserId(), entity.getPlantId(), entity.getReservationId(),
            EntryStatus.valueOf(entity.getStatus()), entity.getJoinedAt());
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaTagRepository.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.Tag;
import com.plantarena.tournaments.domain.TagRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Реализация порта TagRepository. */
@Repository
@Transactional
public class JpaTagRepository implements TagRepository {

    private final TagJpaRepository tags;

    public JpaTagRepository(TagJpaRepository tags) {
        this.tags = tags;
    }

    @Override
    public Tag save(Tag tag) {
        TagJpaEntity entity = tags.findById(tag.id())
            .orElseGet(() -> new TagJpaEntity(tag.id(), tag.name(), tag.createdAt()));
        entity.update(tag.name());
        return toDomain(tags.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tag> findById(UUID id) {
        return tags.findById(id).map(JpaTagRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tag> findByName(String name) {
        return tags.findByName(name).map(JpaTagRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Tag> findAll(int offset, int size) {
        return tags.findAllByOrderByNameAscIdAsc(PageRequest.of(offset / size, size,
                Sort.unsorted())).stream().map(JpaTagRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return tags.count();
    }

    @Override
    public void delete(UUID id) {
        tags.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isUsedByTournament(UUID tagId) {
        return tags.isUsedByTournament(tagId);
    }

    private static Tag toDomain(TagJpaEntity entity) {
        return Tag.restore(entity.getId(), entity.getName(), entity.getCreatedAt());
    }
}
```

- [ ] **Step 6: JPA контрактные IT (Testcontainers)**

`src/test/java/com/plantarena/tournaments/adapter/out/persistence/JpaTournamentRepositoryContractIT.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.support.PostgresSupport;
import com.plantarena.tournaments.TournamentRepositoryContractTest;
import com.plantarena.tournaments.domain.TagRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Контракт TournamentRepository на JPA + PostgreSQL (Testcontainers, раздел
 * 14.2). Миграции выполняет SchemaMigrationConfig (ADR-003).
 */
@DisplayName("Контракт TournamentRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaTournamentRepository.class, JpaTagRepository.class})
class JpaTournamentRepositoryContractIT extends TournamentRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaTournamentRepository repository;

    @Autowired
    private JpaTagRepository tags;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_турниры_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from tournaments.tournament_tag");
        jdbcTemplate.update("delete from tournaments.invitation");
        jdbcTemplate.update("delete from tournaments.tournament_entry");
        jdbcTemplate.update("delete from tournaments.tournament");
        jdbcTemplate.update("delete from tournaments.tag");
    }

    @Override
    protected TournamentRepository repository() {
        return repository;
    }

    @Override
    protected TagRepository tags() {
        return tags;
    }
}
```

`src/test/java/com/plantarena/tournaments/adapter/out/persistence/JpaInvitationRepositoryContractIT.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.support.PostgresSupport;
import com.plantarena.tournaments.InvitationRepositoryContractTest;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Контракт InvitationRepository на JPA + PostgreSQL (UNIQUE-пара — БД). */
@DisplayName("Контракт InvitationRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaTournamentRepository.class,
    JpaInvitationRepository.class, JpaTagRepository.class})
class JpaInvitationRepositoryContractIT extends InvitationRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaInvitationRepository repository;

    @Autowired
    private JpaTournamentRepository tournaments;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_приглашения_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from tournaments.tournament_tag");
        jdbcTemplate.update("delete from tournaments.invitation");
        jdbcTemplate.update("delete from tournaments.tournament_entry");
        jdbcTemplate.update("delete from tournaments.tournament");
        jdbcTemplate.update("delete from tournaments.tag");
    }

    @Override
    protected InvitationRepository repository() {
        return repository;
    }

    @Override
    protected TournamentRepository tournaments() {
        return tournaments;
    }
}
```

`src/test/java/com/plantarena/tournaments/adapter/out/persistence/JpaTournamentEntryRepositoryContractIT.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.support.PostgresSupport;
import com.plantarena.tournaments.TournamentEntryRepositoryContractTest;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Контракт TournamentEntryRepository на JPA + PostgreSQL. */
@DisplayName("Контракт TournamentEntryRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaTournamentRepository.class,
    JpaTournamentEntryRepository.class, JpaTagRepository.class})
class JpaTournamentEntryRepositoryContractIT extends TournamentEntryRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaTournamentEntryRepository repository;

    @Autowired
    private JpaTournamentRepository tournaments;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_участия_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from tournaments.tournament_tag");
        jdbcTemplate.update("delete from tournaments.invitation");
        jdbcTemplate.update("delete from tournaments.tournament_entry");
        jdbcTemplate.update("delete from tournaments.tournament");
        jdbcTemplate.update("delete from tournaments.tag");
    }

    @Override
    protected TournamentEntryRepository repository() {
        return repository;
    }

    @Override
    protected TournamentRepository tournaments() {
        return tournaments;
    }
}
```

`src/test/java/com/plantarena/tournaments/adapter/out/persistence/JpaTagRepositoryContractIT.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.support.PostgresSupport;
import com.plantarena.tournaments.TagRepositoryContractTest;
import com.plantarena.tournaments.domain.TagRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Контракт TagRepository на JPA + PostgreSQL. */
@DisplayName("Контракт TagRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaTournamentRepository.class, JpaTagRepository.class})
class JpaTagRepositoryContractIT extends TagRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaTagRepository repository;

    @Autowired
    private JpaTournamentRepository tournaments;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_теги_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from tournaments.tournament_tag");
        jdbcTemplate.update("delete from tournaments.invitation");
        jdbcTemplate.update("delete from tournaments.tournament_entry");
        jdbcTemplate.update("delete from tournaments.tournament");
        jdbcTemplate.update("delete from tournaments.tag");
    }

    @Override
    protected TagRepository repository() {
        return repository;
    }

    @Override
    protected TournamentRepository tournaments() {
        return tournaments;
    }
}
```

- [ ] **Step 7: Запустить — зелёный**

```bash
./mvnw -q verify -Dit.test='JpaTournamentRepositoryContractIT,JpaInvitationRepositoryContractIT,JpaTournamentEntryRepositoryContractIT,JpaTagRepositoryContractIT' -DfailIfNoTests=false
```

Ожидание: PASS (4 × контрактных набора: 4+4+2+1 тестов). Hibernate `validate` подтверждает соответствие миграции и JPA-моделей.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/tournaments src/main/java/com/plantarena/tournaments/adapter/out/persistence src/test/java/com/plantarena/tournaments
git commit -m "feat(tournaments): хранилище — миграция V2, JPA-адаптеры, контрактные тесты (in-memory + Testcontainers)"
```

### Task 7: Связка — ACL plants/identity, слушатель PlantModerationDecided, poller, demo-ручка, REST; TournamentsApiIT зелёный

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/plants/InProcessPlantEligibility.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/plants/InProcessPlantDirectory.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/identity/InProcessParticipantDirectory.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/events/PlantModerationDecidedHandler.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/jobs/TournamentDeadlinePoller.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/DemoJobsController.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/TournamentController.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/InvitationController.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/TagController.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/TournamentsExceptionHandler.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/CreateTournamentRequest.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/UpdateTournamentRequest.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/TournamentResponse.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/InvitationResponse.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/EntryResponse.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/AcceptInvitationRequest.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/InviteUserRequest.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/CreateTagRequest.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/UpdateTagRequest.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/TagResponse.java`
- Test: `src/test/java/com/plantarena/tournaments/TournamentsApiIT.java` (уже красный, становится зелёным)

**Interfaces:**
- Consumes: порты in/out Tasks 4–5; `plants.api.{PlantEligibility, PlantDirectory, PlantData, PlantModerationStatus, PlantNotEligibleException, ReservationConflictException, PlantNotFoundException}`, `plants.api.event.PlantModerationDecidedEvent` (итерации 3–4 и Task 1); `identity.api.UserDirectory` (Task 1); бины `Clock` (ClockConfig), `IntegrationEventPublisher` (EventWiringConfig), `@EnableScheduling` (ModerationWiringConfig — уже включён, нового wiring-config не нужно); `CurrentActorProvider`, `PaginationParams`, `ApiError` (shared).
- Produces: полная связка контекста tournaments (REST раздела 13, scheduler, подписка на событие); зелёный `TournamentsApiIT`.

- [ ] **Step 1: ACL-адаптеры out (покрываются приёмочным IT, своих тестов не имеют)**

`src/main/java/com/plantarena/tournaments/adapter/out/plants/InProcessPlantEligibility.java`:

```java
package com.plantarena.tournaments.adapter.out.plants;

import com.plantarena.plants.api.PlantEligibility;
import com.plantarena.plants.api.PlantNotEligibleException;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.ReservationConflictException;
import com.plantarena.tournaments.application.ImageAlreadyReservedException;
import com.plantarena.tournaments.application.InvitedPlantNotFoundException;
import com.plantarena.tournaments.application.PlantNotReservableException;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL-адаптер plants → tournaments (раздел 4.3, Consumer-driven): команды
 * допуска plants.api.PlantEligibility; исключения plants переводятся в
 * единый язык tournaments (retryAt — срок временного запрета изображения,
 * у остальных проверок — null). В лабе №2 меняется на HTTP-клиент
 * plant-service с той же трансляцией ошибок.
 */
@Component
public class InProcessPlantEligibility implements PlantEligibilityGateway {

    private final PlantEligibility plantEligibility;

    public InProcessPlantEligibility(PlantEligibility plantEligibility) {
        this.plantEligibility = plantEligibility;
    }

    @Override
    public UUID reserve(UUID ownerId, UUID plantId, UUID idempotencyKey) {
        try {
            return plantEligibility.reserveSubmission(ownerId, plantId, idempotencyKey);
        } catch (PlantNotEligibleException e) {
            throw new PlantNotReservableException(e.getMessage(), e.restrictedUntil());
        } catch (ReservationConflictException e) {
            throw new ImageAlreadyReservedException(e.getMessage());
        } catch (PlantNotFoundException e) {
            throw new InvitedPlantNotFoundException(e.getMessage());
        }
    }

    @Override
    public void confirm(UUID ownerId, UUID plantId, UUID reservationId) {
        try {
            plantEligibility.confirmEligibility(ownerId, plantId, reservationId);
        } catch (PlantNotEligibleException e) {
            throw new PlantNotReservableException(e.getMessage(), e.restrictedUntil());
        } catch (PlantNotFoundException e) {
            throw new InvitedPlantNotFoundException(e.getMessage());
        }
    }

    @Override
    public void release(UUID reservationId) {
        plantEligibility.releaseReservation(reservationId);
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/plants/InProcessPlantDirectory.java`:

```java
package com.plantarena.tournaments.adapter.out.plants;

import com.plantarena.plants.api.PlantData;
import com.plantarena.plants.api.PlantDirectory;
import com.plantarena.plants.api.PlantModerationStatus;
import com.plantarena.tournaments.application.port.out.PlantDirectoryGateway;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL-адаптер read-контракта plants.api.PlantDirectory (дизайн итерации 5,
 * решение 6): PlantData → минимальный снимок tournaments (владелец + факт
 * одобрения модерацией). Права не проверяются — внутренний контракт
 * монолита, видимость решает tournaments. В лабе №2 — HTTP-клиент.
 */
@Component
public class InProcessPlantDirectory implements PlantDirectoryGateway {

    private final PlantDirectory plantDirectory;

    public InProcessPlantDirectory(PlantDirectory plantDirectory) {
        this.plantDirectory = plantDirectory;
    }

    @Override
    public Optional<PlantSnapshot> findById(UUID plantId) {
        return plantDirectory.findById(plantId)
            .map(this::toSnapshot);
    }

    private PlantSnapshot toSnapshot(PlantData plant) {
        return new PlantSnapshot(plant.id(), plant.ownerId(),
            plant.moderationStatus() == PlantModerationStatus.APPROVED);
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/identity/InProcessParticipantDirectory.java`:

```java
package com.plantarena.tournaments.adapter.out.identity;

import com.plantarena.identity.api.UserDirectory;
import com.plantarena.tournaments.application.port.out.ParticipantDirectoryGateway;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL-адаптер identity → tournaments (раздел 4.3): проверка известного
 * активного пользователя при приглашении через OHS-контракт
 * identity.api.UserDirectory (первое api-пакет identity, дизайн итерации 5,
 * решение 6). В лабе №2 меняется на HTTP-клиент.
 */
@Component
public class InProcessParticipantDirectory implements ParticipantDirectoryGateway {

    private final UserDirectory userDirectory;

    public InProcessParticipantDirectory(UserDirectory userDirectory) {
        this.userDirectory = userDirectory;
    }

    @Override
    public boolean isKnownUser(UUID userId) {
        return userDirectory.findById(userId)
            .map(UserDirectory.UserData::active)
            .orElse(false);
    }
}
```

- [ ] **Step 2: Слушатель события, poller, demo-ручка**

`src/main/java/com/plantarena/tournaments/adapter/in/events/PlantModerationDecidedHandler.java`:

```java
package com.plantarena.tournaments.adapter.in.events;

import com.plantarena.plants.api.event.PlantModerationDecidedEvent;
import com.plantarena.tournaments.application.port.in.OnPlantModerationDecidedUseCase;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Подписка на PlantModerationDecided (in-process Spring-событие, конверт
 * IntegrationEvent): tournaments — downstream plants (раздел 4.3). Слушатель
 * живёт в adapter.in.events — ACL-место по context map. Синхронный вызов
 * внутри tx применения решения модерации: перевод заявки в READY/возврат в
 * INVITED атомарен с решением и DONE задания (раздел 12, ADR-010). В лабе
 * №4 заменяется Kafka-слушателем без изменения use case.
 */
@Component
public class PlantModerationDecidedHandler {

    private final OnPlantModerationDecidedUseCase onPlantModerationDecided;

    public PlantModerationDecidedHandler(OnPlantModerationDecidedUseCase onPlantModerationDecided) {
        this.onPlantModerationDecided = onPlantModerationDecided;
    }

    @EventListener
    public void onPlantModerationDecided(PlantModerationDecidedEvent event) {
        onPlantModerationDecided.onPlantModerationDecided(
            event.payload().plantId(), event.payload().decision());
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/jobs/TournamentDeadlinePoller.java`:

```java
package com.plantarena.tournaments.adapter.in.jobs;

import com.plantarena.tournaments.application.port.in.StartTournamentUseCase;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Опрос наступивших дедлайнов регистрации: fixedDelay 2с, пачка ≤ 10.
 * Тот же use case, что ручка POST /tournaments/{id}/start и demo-ручка
 * (раздел 7, дизайн итерации 5, решение 1); per-tournament tx и
 * устойчивость к сбоям решает startDue. Планировщик уже включён
 * (ModerationWiringConfig, @EnableScheduling) — нового config не нужно.
 */
@Component
public class TournamentDeadlinePoller {

    private static final Logger log = LoggerFactory.getLogger(TournamentDeadlinePoller.class);
    private static final int BATCH_SIZE = 10;

    private final StartTournamentUseCase startTournament;
    private final Clock clock;

    public TournamentDeadlinePoller(StartTournamentUseCase startTournament, Clock clock) {
        this.startTournament = startTournament;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        try {
            startTournament.startDue(clock.instant(), BATCH_SIZE);
        } catch (RuntimeException e) {
            log.error("Цикл обработки дедлайнов турниров не удался (продолжаем): {}",
                e.getMessage());
        }
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/DemoJobsController.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.tournaments.application.TournamentsAccessPolicy;
import com.plantarena.tournaments.application.port.in.StartTournamentUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Диагностика (раздел 13): обработка наступивших дедлайнов через тот же use
 * case, что scheduler и ручка старта — без обхода правил. Только профили
 * dev/test (в обычной конфигурации бин отсутствует); доступ — M/A. Время —
 * только Clock (публичной ручки времени нет); отдельный Swagger tag.
 */
@RestController
@RequestMapping("/api/v1/internal/demo/jobs")
@Profile({"dev", "test"})
@Tag(name = "demo")
public class DemoJobsController {

    private final StartTournamentUseCase startTournament;
    private final TournamentsAccessPolicy accessPolicy;
    private final CurrentActorProvider currentActorProvider;
    private final Clock clock;

    public DemoJobsController(StartTournamentUseCase startTournament,
                              TournamentsAccessPolicy accessPolicy,
                              CurrentActorProvider currentActorProvider, Clock clock) {
        this.startTournament = startTournament;
        this.accessPolicy = accessPolicy;
        this.currentActorProvider = currentActorProvider;
        this.clock = clock;
    }

    @PostMapping("/run-due")
    @Operation(operationId = "demo-run-due-jobs",
        summary = "Обработать наступившие дедлайны турниров (dev/test, M/A)")
    public Map<String, Integer> runDue() {
        CurrentActor actor = currentActorProvider.currentActor();
        accessPolicy.requireModeratorOrAdmin(actor);
        return Map.of("processed", startTournament.startDue(clock.instant(), 10));
    }
}
```

- [ ] **Step 3: Web-DTO (jakarta validation — зеркала правил домена, чтобы неверный ввод был 400, а не 500)**

`src/main/java/com/plantarena/tournaments/adapter/in/web/CreateTournamentRequest.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Тело POST /tournaments (раздел 13): параметры PRIVATE DRAFT. */
public record CreateTournamentRequest(
        @NotBlank @Size(min = 1, max = 100) String name,
        @Size(max = 2000) String description,
        @NotNull @Future Instant registrationDeadline,
        @NotNull @Positive Long roundDurationSeconds,
        @NotNull @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "1", inclusive = false) Double eliminationFraction,
        @NotNull @Min(2) Integer minParticipants,
        Set<UUID> tagIds) {
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/UpdateTournamentRequest.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Тело PATCH /tournaments/{id}: null = не менять (пустое имя недопустимо);
 * параметры применимы только в DRAFT, безопасное описание — и после
 * открытия (раздел 13).
 */
public record UpdateTournamentRequest(
        @Size(min = 1, max = 100) String name,
        @Size(max = 2000) String description,
        @Future Instant registrationDeadline,
        @Positive Long roundDurationSeconds,
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "1", inclusive = false) Double eliminationFraction,
        @Min(2) Integer minParticipants,
        Set<UUID> tagIds) {
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/TournamentResponse.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.api.TournamentData;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Ответ REST по турниру (раздел 13): reservationId и детали заявок не раскрываются. */
public record TournamentResponse(
        UUID id,
        UUID creatorId,
        String name,
        String description,
        String type,
        String status,
        String algorithm,
        Instant registrationDeadline,
        long roundDurationSeconds,
        double eliminationFraction,
        int minParticipants,
        String cancelReason,
        Set<UUID> tagIds,
        Instant createdAt) {

    public static TournamentResponse from(TournamentData data) {
        return new TournamentResponse(data.id(), data.creatorId(), data.name(),
            data.description(), data.type(), data.status(), data.algorithm(),
            data.registrationDeadline(), data.roundDurationSeconds(),
            data.eliminationFraction(), data.minParticipants(), data.cancelReason(),
            data.tagIds(), data.createdAt());
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/InvitationResponse.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.api.InvitationData;
import java.time.Instant;
import java.util.UUID;

/** Ответ REST по приглашению (раздел 13): reservationId не раскрывается. */
public record InvitationResponse(
        UUID id,
        UUID tournamentId,
        UUID userId,
        String status,
        Instant invitedAt,
        Instant respondedAt,
        UUID submittedPlantId) {

    public static InvitationResponse from(InvitationData data) {
        return new InvitationResponse(data.id(), data.tournamentId(), data.userId(),
            data.status(), data.invitedAt(), data.respondedAt(), data.submittedPlantId());
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/AcceptInvitationRequest.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Тело POST /invitations/{id}/accept (раздел 13): своё растение. */
public record AcceptInvitationRequest(@NotNull UUID plantId) {
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/InviteUserRequest.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Тело POST /tournaments/{id}/invitations (раздел 13). */
public record InviteUserRequest(@NotNull UUID userId) {
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/CreateTagRequest.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Тело POST /tags (раздел 13). */
public record CreateTagRequest(@NotBlank @Size(min = 1, max = 100) String name) {
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/UpdateTagRequest.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Тело PATCH /tags/{id} (раздел 13). */
public record UpdateTagRequest(@NotBlank @Size(min = 1, max = 100) String name) {
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/TagResponse.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.api.TagData;
import java.util.UUID;

/** Ответ REST по тегу справочника (раздел 13). */
public record TagResponse(UUID id, String name) {

    public static TagResponse from(TagData data) {
        return new TagResponse(data.id(), data.name());
    }
}
```

- [ ] **Step 4: Контроллеры и exception handler**

`src/main/java/com/plantarena/tournaments/adapter/in/web/TournamentController.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.shared.web.PaginationParams;
import com.plantarena.tournaments.api.TournamentData;
import com.plantarena.tournaments.application.port.in.CancelTournamentUseCase;
import com.plantarena.tournaments.application.port.in.CreateTournamentUseCase;
import com.plantarena.tournaments.application.port.in.DeleteTournamentUseCase;
import com.plantarena.tournaments.application.port.in.GetTournamentUseCase;
import com.plantarena.tournaments.application.port.in.InviteUserUseCase;
import com.plantarena.tournaments.application.port.in.ListEntriesUseCase;
import com.plantarena.tournaments.application.port.in.ListInvitationsUseCase;
import com.plantarena.tournaments.application.port.in.ListTournamentsUseCase;
import com.plantarena.tournaments.application.port.in.OpenRegistrationUseCase;
import com.plantarena.tournaments.application.port.in.RevokeInvitationUseCase;
import com.plantarena.tournaments.application.port.in.StartTournamentUseCase;
import com.plantarena.tournaments.application.port.in.UpdateTournamentUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
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
 * Ручки турниров (раздел 13). Контроллер обращается только к входным портам
 * application; видимость (404 скрытого) и права решают use case/AccessPolicy.
 * Фильтр статуса — строкой (разбор в application, LayerRules: web не зависит
 * от domain).
 */
@RestController
@RequestMapping("/api/v1/tournaments")
@Tag(name = "tournaments")
public class TournamentController {

    private final CreateTournamentUseCase createTournament;
    private final UpdateTournamentUseCase updateTournament;
    private final DeleteTournamentUseCase deleteTournament;
    private final OpenRegistrationUseCase openRegistration;
    private final CancelTournamentUseCase cancelTournament;
    private final StartTournamentUseCase startTournament;
    private final ListTournamentsUseCase listTournaments;
    private final GetTournamentUseCase getTournament;
    private final ListEntriesUseCase listEntries;
    private final InviteUserUseCase inviteUser;
    private final RevokeInvitationUseCase revokeInvitation;
    private final ListInvitationsUseCase listInvitations;
    private final CurrentActorProvider currentActorProvider;

    public TournamentController(CreateTournamentUseCase createTournament,
                                UpdateTournamentUseCase updateTournament,
                                DeleteTournamentUseCase deleteTournament,
                                OpenRegistrationUseCase openRegistration,
                                CancelTournamentUseCase cancelTournament,
                                StartTournamentUseCase startTournament,
                                ListTournamentsUseCase listTournaments,
                                GetTournamentUseCase getTournament,
                                ListEntriesUseCase listEntries,
                                InviteUserUseCase inviteUser,
                                RevokeInvitationUseCase revokeInvitation,
                                ListInvitationsUseCase listInvitations,
                                CurrentActorProvider currentActorProvider) {
        this.createTournament = createTournament;
        this.updateTournament = updateTournament;
        this.deleteTournament = deleteTournament;
        this.openRegistration = openRegistration;
        this.cancelTournament = cancelTournament;
        this.startTournament = startTournament;
        this.listTournaments = listTournaments;
        this.getTournament = getTournament;
        this.listEntries = listEntries;
        this.inviteUser = inviteUser;
        this.revokeInvitation = revokeInvitation;
        this.listInvitations = listInvitations;
        this.currentActorProvider = currentActorProvider;
    }

    @PostMapping
    @Operation(operationId = "tournaments-create",
        summary = "Создать PRIVATE DRAFT (модератор/админ)")
    public ResponseEntity<TournamentResponse> create(
            @Valid @RequestBody CreateTournamentRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        TournamentData tournament = createTournament.create(actor,
            new CreateTournamentUseCase.CreateTournamentCommand(request.name(),
                request.description(), request.registrationDeadline(),
                Duration.ofSeconds(request.roundDurationSeconds()),
                request.eliminationFraction(), request.minParticipants(),
                request.tagIds() == null ? Set.of() : request.tagIds()));
        return ResponseEntity
            .created(URI.create("/api/v1/tournaments/" + tournament.id()))
            .body(TournamentResponse.from(tournament));
    }

    @GetMapping
    @Operation(operationId = "tournaments-list",
        summary = "Список доступных турниров (фильтры status/tag, X-Total-Count)")
    public ResponseEntity<List<TournamentResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID tagId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        ListTournamentsUseCase.TournamentListResult result =
            listTournaments.list(actor, status, tagId, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(result.items().stream().map(TournamentResponse::from).toList());
    }

    @GetMapping("/{id}")
    @Operation(operationId = "tournaments-get",
        summary = "Турнир (организатор/админ/приглашённый/участник; скрытый — 404)")
    public TournamentResponse get(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return TournamentResponse.from(getTournament.get(actor, id));
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "tournaments-update",
        summary = "Изменить турнир (параметры — только DRAFT, описание — безопасное)")
    public TournamentResponse update(@PathVariable UUID id,
                                     @Valid @RequestBody UpdateTournamentRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        return TournamentResponse.from(updateTournament.update(actor, id,
            new UpdateTournamentUseCase.UpdateTournamentCommand(request.name(),
                request.description(), request.registrationDeadline(),
                request.roundDurationSeconds() == null ? null
                    : Duration.ofSeconds(request.roundDurationSeconds()),
                request.eliminationFraction(), request.minParticipants(), request.tagIds())));
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "tournaments-delete",
        summary = "Удалить пустой DRAFT (иначе 409)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        deleteTournament.delete(actor, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/open-registration")
    @Operation(operationId = "tournaments-open-registration",
        summary = "Открыть приём заявок (DRAFT → REGISTRATION_OPEN)")
    public TournamentResponse openRegistration(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return TournamentResponse.from(openRegistration.openRegistration(actor, id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(operationId = "tournaments-cancel",
        summary = "Отменить турнир до RUNNING с освобождением резервов")
    public TournamentResponse cancel(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return TournamentResponse.from(cancelTournament.cancel(actor, id));
    }

    @PostMapping("/{id}/start")
    @Operation(operationId = "tournaments-start",
        summary = "Стартовать вручную (тот же use case, что scheduler; только после дедлайна)")
    public TournamentResponse start(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return TournamentResponse.from(startTournament.start(actor, id));
    }

    @PostMapping("/{id}/invitations")
    @Operation(operationId = "tournaments-invite-user",
        summary = "Пригласить пользователя (организатор, до дедлайна; дубль пары — 409)")
    public ResponseEntity<InvitationResponse> invite(@PathVariable UUID id,
                                                     @Valid @RequestBody InviteUserRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        var invitation = inviteUser.invite(actor, id, request.userId());
        return ResponseEntity
            .created(URI.create("/api/v1/tournaments/" + id + "/invitations/"
                + invitation.id()))
            .body(InvitationResponse.from(invitation));
    }

    @GetMapping("/{id}/invitations")
    @Operation(operationId = "tournaments-list-invitations",
        summary = "Приглашения турнира (организатор/админ, X-Total-Count)")
    public ResponseEntity<List<InvitationResponse>> listInvitations(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        ListInvitationsUseCase.InvitationListResult result =
            listInvitations.list(actor, id, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(result.items().stream().map(InvitationResponse::from).toList());
    }

    @DeleteMapping("/{id}/invitations/{invitationId}")
    @Operation(operationId = "tournaments-revoke-invitation",
        summary = "Отозвать не принятое приглашение (REVOKED, история сохраняется)")
    public ResponseEntity<Void> revokeInvitation(@PathVariable UUID id,
                                                 @PathVariable UUID invitationId) {
        CurrentActor actor = currentActorProvider.currentActor();
        revokeInvitation.revoke(actor, id, invitationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/entries")
    @Operation(operationId = "tournaments-list-entries",
        summary = "Участники/результаты турнира (имеющие доступ, X-Total-Count)")
    public ResponseEntity<List<EntryResponse>> listEntries(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        ListEntriesUseCase.EntryListResult result =
            listEntries.list(actor, id, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(result.items().stream().map(EntryResponse::from).toList());
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/EntryResponse.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.api.EntryData;
import java.time.Instant;
import java.util.UUID;

/** Ответ REST по участию (раздел 13). */
public record EntryResponse(
        UUID id,
        UUID tournamentId,
        UUID userId,
        UUID plantId,
        String status,
        Instant joinedAt) {

    public static EntryResponse from(EntryData data) {
        return new EntryResponse(data.id(), data.tournamentId(), data.userId(),
            data.plantId(), data.status(), data.joinedAt());
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/InvitationController.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.shared.web.PaginationParams;
import com.plantarena.tournaments.application.port.in.AcceptInvitationUseCase;
import com.plantarena.tournaments.application.port.in.DeclineInvitationUseCase;
import com.plantarena.tournaments.application.port.in.ListInvitationsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ручки приглашений (раздел 13): свои приглашения (/me/invitations) и
 * принятие/отказ адресатом. Чужое приглашение скрыто (404) — решает use case.
 */
@RestController
@Tag(name = "tournaments")
public class InvitationController {

    private final AcceptInvitationUseCase acceptInvitation;
    private final DeclineInvitationUseCase declineInvitation;
    private final ListInvitationsUseCase listInvitations;
    private final CurrentActorProvider currentActorProvider;

    public InvitationController(AcceptInvitationUseCase acceptInvitation,
                                DeclineInvitationUseCase declineInvitation,
                                ListInvitationsUseCase listInvitations,
                                CurrentActorProvider currentActorProvider) {
        this.acceptInvitation = acceptInvitation;
        this.declineInvitation = declineInvitation;
        this.listInvitations = listInvitations;
        this.currentActorProvider = currentActorProvider;
    }

    @GetMapping("/api/v1/me/invitations")
    @Operation(operationId = "invitations-list-mine",
        summary = "Свои приглашения (X-Total-Count)")
    public ResponseEntity<List<InvitationResponse>> listMine(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        ListInvitationsUseCase.InvitationListResult result =
            listInvitations.listMine(actor, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(result.items().stream().map(InvitationResponse::from).toList());
    }

    @PostMapping("/api/v1/invitations/{id}/accept")
    @Operation(operationId = "invitations-accept",
        summary = "Принять приглашение со своим растением (до дедлайна; резерв в той же tx)")
    public InvitationResponse accept(@PathVariable UUID id,
                                     @Valid @RequestBody AcceptInvitationRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        return InvitationResponse.from(acceptInvitation.accept(actor, id, request.plantId()));
    }

    @PostMapping("/api/v1/invitations/{id}/decline")
    @Operation(operationId = "invitations-decline",
        summary = "Отказаться от приглашения до старта (резерв освобождается)")
    public InvitationResponse decline(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return InvitationResponse.from(declineInvitation.decline(actor, id));
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/TagController.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.shared.web.PaginationParams;
import com.plantarena.tournaments.application.port.in.TagsUseCase;
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
 * Справочник тегов (раздел 13): создание — M/A, изменение/удаление — A
 * (используемый тег не удаляется — 409), чтение — все идентифицированные.
 */
@RestController
@RequestMapping("/api/v1/tags")
@Tag(name = "tournaments")
public class TagController {

    private final TagsUseCase tags;
    private final CurrentActorProvider currentActorProvider;

    public TagController(TagsUseCase tags, CurrentActorProvider currentActorProvider) {
        this.tags = tags;
        this.currentActorProvider = currentActorProvider;
    }

    @PostMapping
    @Operation(operationId = "tags-create", summary = "Создать тег (модератор/админ)")
    public ResponseEntity<TagResponse> create(@Valid @RequestBody CreateTagRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        var tag = tags.create(actor, request.name());
        return ResponseEntity
            .created(URI.create("/api/v1/tags/" + tag.id()))
            .body(TagResponse.from(tag));
    }

    @GetMapping
    @Operation(operationId = "tags-list", summary = "Справочник тегов (X-Total-Count)")
    public ResponseEntity<List<TagResponse>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        TagsUseCase.TagListResult result = tags.list(actor, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(result.items().stream().map(TagResponse::from).toList());
    }

    @GetMapping("/{id}")
    @Operation(operationId = "tags-get", summary = "Тег по id")
    public TagResponse get(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return TagResponse.from(tags.get(actor, id));
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "tags-update", summary = "Переименовать тег (админ)")
    public TagResponse update(@PathVariable UUID id,
                              @Valid @RequestBody UpdateTagRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        return TagResponse.from(tags.rename(actor, id, request.name()));
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "tags-delete",
        summary = "Удалить тег (админ; используемый турниром — 409)")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        tags.delete(actor, id);
        return ResponseEntity.noContent().build();
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/TournamentsExceptionHandler.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.web.ApiError;
import com.plantarena.shared.web.TraceIdFilter;
import com.plantarena.tournaments.application.DuplicateInvitationException;
import com.plantarena.tournaments.application.ImageAlreadyReservedException;
import com.plantarena.tournaments.application.InvitationNotFoundException;
import com.plantarena.tournaments.application.InvitedPlantNotFoundException;
import com.plantarena.tournaments.application.PlantNotReservableException;
import com.plantarena.tournaments.application.RegistrationClosedException;
import com.plantarena.tournaments.application.TagAlreadyExistsException;
import com.plantarena.tournaments.application.TagInUseException;
import com.plantarena.tournaments.application.TagNotFoundException;
import com.plantarena.tournaments.application.TournamentNotFoundException;
import com.plantarena.tournaments.application.TournamentStateConflictException;
import com.plantarena.tournaments.application.UnknownStatusFilterException;
import com.plantarena.tournaments.application.UnknownUserException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Перевод исключений tournaments в ProblemDetail-подобное тело (раздел 13:
 * скрытое — 404, конфликты состояний/дедлайна/дублей — 409, неизвестный
 * фильтр — 400). PLANT_NOT_RESERVABLE несёт retryAt — срок временного
 * запрета изображения (COOLDOWN), у остальных — null.
 */
@RestControllerAdvice
public class TournamentsExceptionHandler {

    @ExceptionHandler(TournamentNotFoundException.class)
    public ResponseEntity<ApiError> tournamentNotFound(TournamentNotFoundException e,
                                                       HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "TOURNAMENT_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(InvitationNotFoundException.class)
    public ResponseEntity<ApiError> invitationNotFound(InvitationNotFoundException e,
                                                       HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(TagNotFoundException.class)
    public ResponseEntity<ApiError> tagNotFound(TagNotFoundException e,
                                                HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "TAG_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(UnknownUserException.class)
    public ResponseEntity<ApiError> unknownUser(UnknownUserException e,
                                                HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "UNKNOWN_USER", e.getMessage(), request);
    }

    @ExceptionHandler(InvitedPlantNotFoundException.class)
    public ResponseEntity<ApiError> plantNotFound(InvitedPlantNotFoundException e,
                                                  HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "INVITED_PLANT_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(TournamentStateConflictException.class)
    public ResponseEntity<ApiError> stateConflict(TournamentStateConflictException e,
                                                  HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "TOURNAMENT_STATE_CONFLICT", e.getMessage(), request);
    }

    @ExceptionHandler(RegistrationClosedException.class)
    public ResponseEntity<ApiError> registrationClosed(RegistrationClosedException e,
                                                       HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "REGISTRATION_CLOSED", e.getMessage(), request);
    }

    @ExceptionHandler(DuplicateInvitationException.class)
    public ResponseEntity<ApiError> duplicateInvitation(DuplicateInvitationException e,
                                                        HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "DUPLICATE_INVITATION", e.getMessage(), request);
    }

    @ExceptionHandler(TagAlreadyExistsException.class)
    public ResponseEntity<ApiError> tagAlreadyExists(TagAlreadyExistsException e,
                                                     HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "TAG_ALREADY_EXISTS", e.getMessage(), request);
    }

    @ExceptionHandler(TagInUseException.class)
    public ResponseEntity<ApiError> tagInUse(TagInUseException e,
                                             HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "TAG_IN_USE", e.getMessage(), request);
    }

    @ExceptionHandler(ImageAlreadyReservedException.class)
    public ResponseEntity<ApiError> imageAlreadyReserved(ImageAlreadyReservedException e,
                                                         HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "IMAGE_ALREADY_RESERVED", e.getMessage(), request);
    }

    /** 409 + retryAt: срок истечения временного запрета изображения (null — бессрочный/нет). */
    @ExceptionHandler(PlantNotReservableException.class)
    public ResponseEntity<ApiError> plantNotReservable(PlantNotReservableException e,
                                                       HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
            URI.create("about:blank"), HttpStatus.CONFLICT.getReasonPhrase(),
            HttpStatus.CONFLICT.value(), e.getMessage(),
            URI.create(request.getRequestURI()), "PLANT_NOT_RESERVABLE", List.of(), traceId,
            e.retryAt()));
    }

    @ExceptionHandler(UnknownStatusFilterException.class)
    public ResponseEntity<ApiError> unknownStatusFilter(UnknownStatusFilterException e,
                                                        HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "UNKNOWN_STATUS_FILTER", e.getMessage(), request);
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

- [ ] **Step 5: ArchUnit и зелёный приёмочный IT**

```bash
./mvnw -q test -Dtest='ContextBoundaryTest,LayerRulesTest'
```

Ожидание: PASS — адаптеры tournaments обращаются к чужим контекстам только через `plants.api`/`identity.api` и только из `adapter.out.*`/`adapter.in.events`; контроллеры не зависят от `domain` (фильтр статуса — строка) и не трогают репозитории.

```bash
./mvnw -q verify -Dit.test=TournamentsApiIT -DfailIfNoTests=false
```

Ожидание: `TournamentsApiIT` PASS (5 тестов): полный цикл до RUNNING с участниками и EXPIRED; недостаток участников → CANCELLED + INSUFFICIENT_PARTICIPANTS + освобождённый резерв (архивация растения проходит); отзыв/скрытие/чужое растение; теги; 401/403. Если падает по таймауту Awaitility — смотреть лог: poller должен запускаться каждые 2с (`@EnableScheduling` уже включён `ModerationWiringConfig`), `@Primary`-детерминированный классификатор должен выиграть у ONNX-бина (как в `ModerationApiIT`).

- [ ] **Step 6: Полный verify**

```bash
./mvnw -q verify
```

Ожидание: BUILD SUCCESS — все unit + контрактные + приёмочные IT + ArchUnit зелёные, JaCoCo LINE ≥ 70% (gate в конце verify). В логах других IT возможны WARN классификатора — ожидаемая честная незавершённость (ADR-009).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/plantarena/tournaments/adapter
git commit -m "feat(tournaments): связка — ACL plants/identity, слушатель PlantModerationDecided, poller, demo-ручка, REST; приёмочный IT зелёный"
```

### Task 8: Документация — ADR-010, глоссарий, агрегаты, context map, README; финальная проверка и merge

**Files:**
- Create: `docs/domain/adr/ADR-010-tournament-cross-context-tx.md`
- Modify: `docs/domain/glossary.md`
- Modify: `docs/domain/aggregates.md`
- Modify: `docs/domain/context-map.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: всё, реализованное в Tasks 1–7.
- Produces: документация, замыкающая итерацию (Definition of done); ветка `feat/iteration-5-tournaments` слита в `main` (локально, без push).

- [ ] **Step 1: ADR-010**

`docs/domain/adr/ADR-010-tournament-cross-context-tx.md`:

```markdown
# ADR-010: Межконтекстные транзакции итерации 5 — только в монолите

Дата: 2026-09-27. Статус: принято (итерация 5).

## Контекст

Раздел 12 требований: «одна tx — один агрегат», но инварианты итерации 5
связывают агрегаты двух контекстов: принятие приглашения обязано создать
резерв plants; решение модерации обязано перевести заявку (READY / возврат
в INVITED) — «не ставь DONE до обязательного обновления заявки» (раздел 14);
старт подтверждает резервы и создаёт TournamentEntry. В монолите границы
контекстов — пакеты, не процессы.

## Решение

1. **Принятие приглашения — одна tx**: `InvitationService.accept`
   (@Transactional) вызывает ACL-адаптер `PlantEligibilityGateway.reserve`
   (in-process, та же tx): Invitation + резерв plants фиксируются атомарно;
   сбой резерва (409 plants) откатывает заявку в INVITED.
2. **Решение модерации → перевод заявки — синхронно в tx применения**:
   `PlantModerationDecidedHandler` (@EventListener, in-process) вызывается в
   tx `recordDecision`: DONE задания + решение Plant + переход Invitation —
   одна tx (раздел 10.3: обязательное последствие — синхронно). Поздний
   результат (после дедлайна/отмены/терминальных статусов) — no-op с
   сохранением истории подачи.
3. **Старт — per-tournament короткие tx**: `StartTournamentUseCase.startDue`
   берёт due-турниры из БД и запускает каждый в отдельной tx
   (TransactionTemplate): подтверждение резервов + `TournamentEntry.admit` +
   EXPIRED не-READY + `TournamentStarted`; один битый турнир не блокирует
   остальные (повтор — следующий poll). Ручка старта и demo-ручка — тот же
   use case.
4. **Отмена и недостаток участников — освобождение резервов в той же tx**
   (release идемпотентен; растения не погибают — раздел 7).
5. **Ключ идемпотентности резерва — на попытку принятия**: свежий UUID на
   каждое принятие (повторное принятие после REJECTED — новый резерв; тот же
   ключ вернул бы старый reservationId). Идемпотентность HTTP-повтора accept
   — по состоянию: повтор с тем же plantId для принятого приглашения
   возвращает текущий статус без нового резерва.

## Последствия

- Отступление честно ограничено монолитом: в лабе №2 связки становятся
  saga/компенсациями (reserve → confirm/release), перевод заявки —
  идемпотентная команда с retry; в лабе №4 — outbox → Kafka → inbox.
- In-process `@EventListener` — только для обязательных последствий внутри
  монолита; `@TransactionalEventListener(AFTER_COMMIT)` и `@Async` — только
  необязательные побочные эффекты (раздел 10.3).
- `IntegrationEventPublisher` (Spring ApplicationEventPublisher) — точка
  замены на Kafka-издателя без изменения домена/application (ADR-006).
- `TournamentStarted`/`InvitationCreated` публикуются в tx старта/приглашения;
  подписчики — итерации 6–8 (окна, лента) и notification-service лабы №4.
```

- [ ] **Step 2: Глоссарий**

В `docs/domain/glossary.md` после строки «Эпоха отбора» (блок терминов tournaments) добавить:

```markdown
| Тег | `Tag` | tournaments | Тематика турнира (справочник); tagIds — параметры турнира (меняются только в DRAFT), удаление используемого тега запрещено |
```

- [ ] **Step 3: aggregates.md**

В `docs/domain/aggregates.md` заменить строки Tournament/Invitation/TournamentEntry (было «(итерация 5)» / «(итерации 5–7)») и после TournamentEntry добавить строку Tag:

```markdown
| tournaments | `Tournament` | параметры, type, status, version | State machine DRAFT → REGISTRATION_OPEN → RUNNING → FINISHED / CANCELLED; параметры и теги меняются только в DRAFT; старт — после дедлайна при READY ≥ minParticipants; отмена — до RUNNING | создать черновик, открыть регистрацию, стартовать, отменить | `TournamentStarted`, `TournamentFinished` (итерация 6) | `TournamentTest` (state machine/параметры), `TournamentAdministrationServiceTest`, `StartTournamentServiceTest` (старт/недостаток участников), `TournamentRepositoryContractTest` + `JpaTournamentRepositoryContractIT`, `TournamentsApiIT` (через HTTP) |
| tournaments | `Invitation` | tournamentId, userId, статус, submittedPlantId, reservationId | Переходы статусов, дедлайн, уникальность (tournamentId, userId) | пригласить, отозвать, принять, отклонить | `InvitationCreated` | `InvitationTest` (переходы/дедлайн), `InvitationServiceTest`, `PlantModerationReactionServiceTest` (READY/возврат в INVITED), `InvitationRepositoryContractTest` + `JpaInvitationRepositoryContractIT` (UNIQUE-пара), `TournamentsApiIT` |
| tournaments | `TournamentEntry` | tournamentId, userId, plantId, reservationId, статус | State machine для PRIVATE (ACTIVE → ELIMINATED/WINNER) и GLOBAL (QUEUED → QUALIFYING → FINAL_PENDING → FINALIST → ELIMINATED, WITHDRAWN из QUEUED) | допустить к старту, выбыть, победить, сняться | `EntryEliminated` | `TournamentEntryTest` (admit), `StartTournamentServiceTest`, `TournamentEntryRepositoryContractTest` + `JpaTournamentEntryRepositoryContractIT` |
| tournaments | `Tag` | имя | Уникальность имени; используемый турниром тег не удаляется | создать, переименовать, удалить | — | `TagTest`, `TagsServiceTest`, `TagRepositoryContractTest` + `JpaTagRepositoryContractIT` |
```

- [ ] **Step 4: context-map.md**

В `docs/domain/context-map.md`:

1. В mermaid-диаграмме метки рёбер tournaments заменить:
   - `tournaments -->|ACL: CurrentActor, профиль| identity` → `tournaments -->|ACL: CurrentActor, UserDirectory| identity`
   - `tournaments -->|ACL: PlantEligibility, PlantLifecycle| plants` → `tournaments -->|ACL: PlantEligibility, PlantDirectory, PlantLifecycle| plants`
2. В таблице «Допустимые зависимости» строки identity и tournaments заменить на:

```markdown
| identity | — | Upstream для всех; Open Host Service (CurrentActor, публичный профиль, `UserDirectory` — итерация 5) |
| tournaments | identity, plants, geo | Downstream; ACL `PlantEligibility`, `PlantDirectory`, `ParticipantDirectory`, `ClusteringGateway` (geo — итерация 7); подписан на `PlantModerationDecided` (adapter.in.events, та же tx — ADR-010) |
```

3. В «Таблицу взаимодействий» после строки `tournaments | plants | PlantEligibility...` добавить:

```markdown
| tournaments | plants | `PlantDirectory.findById` (read, проверка APPROVED при принятии) | запрос через `plants.api` | синхронно | Feign | Feign |
| tournaments | identity | `UserDirectory.findById` (известный активный пользователь) | запрос через `identity.api` | синхронно | Feign | Feign |
| plants | tournaments | публикация `PlantModerationDecided` (перевод заявки) | событие | синхронно, в tx решения (ADR-010) | outbox → идемпотентная команда | Kafka `plant.moderation.v1` |
```

- [ ] **Step 5: README**

В `README.md` после раздела «Модерация (moderation)» добавить раздел:

```markdown
## Турниры (tournaments)

Закрытые (PRIVATE) турниры с приглашениями (раздел 7): модератор/админ создаёт
DRAFT (параметры + теги), приглашает пользователей; приём заявок — до дедлайна
регистрации. Принятие приглашения резервирует изображение растения (одно
изображение — один активный резерв, ADR-010): одобренное модерацией растение →
заявка READY сразу, идущая модерация → ACCEPTED_PENDING_MODERATION (решение
переводит в READY или возвращает в INVITED). Наступивший дедлайн стартует
турнир scheduler'ом (fixedDelay 2с) или вручную — один use case: READY ≥
minParticipants → RUNNING (участия ACTIVE, не-READY → EXPIRED, резервы
подтверждены); иначе CANCELLED с причиной INSUFFICIENT_PARTICIPANTS и
освобождением резервов (растения не погибают).

- Создание и теги — `POST /api/v1/tournaments`, `POST /api/v1/tags` (M/A);
  список доступных — `GET /api/v1/tournaments?status=&tagId=` (X-Total-Count).
- Приглашения — `POST /api/v1/tournaments/{id}/invitations`,
  `GET /api/v1/me/invitations`, `POST /api/v1/invitations/{id}/accept|decline`.
- Старт/отмена — `POST /api/v1/tournaments/{id}/start|cancel`; участники —
  `GET /api/v1/tournaments/{id}/entries`.
- Диагностика (dev/test, M/A): `POST /api/v1/internal/demo/jobs/run-due` —
  тот же use case, что scheduler, без обхода правил.
- Окна голосования, выбывание, FINISHED — итерация 6; глобальный турнир и
  geo-кластеры — итерация 7.
```

- [ ] **Step 6: Commit документации**

```bash
git add docs README.md
git commit -m "docs: ADR-010 (межконтекстные tx итерации 5), глоссарий, агрегаты, context map, README"
```

- [ ] **Step 7: Полный verify и отчёт о покрытии**

```bash
./mvnw verify
```

Ожидание: BUILD SUCCESS — все unit/application/контрактные/приёмочные IT зелёные (включая `TournamentsApiIT`, 5 тестов), ArchUnit зелёный, JaCoCo LINE ≥ 70%.

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

Ожидание: LINE ≥ 70% (новые классы tournaments покрыты тестами всех уровней — домен, application, контракты репозиториев, HTTP).

- [ ] **Step 8: Merge в main (локально, без push)**

```bash
git checkout main
git merge --no-ff feat/iteration-5-tournaments -m "merge: итерация 5 — tournaments"
./mvnw verify
git log --oneline -3
```

Ожидание: merge без конфликтов; verify на `main` BUILD SUCCESS; push НЕ выполняется (только по отдельной команде пользователя).

- [ ] **Step 9: Чекпоинт-отчёт**

Краткий отчёт пользователю: что готово (агрегаты Tournament/Invitation/TournamentEntry/Tag, контракты `identity.api.UserDirectory` и `plants.api.PlantDirectory`, REST раздела 13 + demo-ручка, scheduler старта, подписка на `PlantModerationDecided`, миграция V2, ADR-010 и docs), результаты verify и покрытия, отклонения от плана (если были), что отложено (окна голосования/выбывание/FINISHED — итерация 6; глобальный турнир и geo — итерация 7; гостевые сессии — итерация 8; уведомления — лаба №4), ссылка на план следующей итерации (6 — окна голосования). Дождаться review перед итерацией 6.

---

## Definition of Done (итерация 5)

- `./mvnw verify` зелёный (unit + контрактные + приёмочные IT + ArchUnit + JaCoCo LINE ≥ 70%).
- Сквозные сценарии раздела 7 через HTTP покрыты `TournamentsApiIT` (Testcontainers, детерминированный классификатор, короткие дедлайны + Awaitility): полный цикл DRAFT → приглашения → заявки → RUNNING с участниками и EXPIRED; отмена при недостатке участников (INSUFFICIENT_PARTICIPANTS) с освобождением резервов; отзыв/скрытие/чужое растение; теги; 401/403.
- Scheduler и ручка старта — один use case (IT: старт по дедлайну выполняет scheduler без ручки).
- Docs замкнуты: ADR-010, глоссарий (Тег), aggregates (защищающие тесты), context-map (UserDirectory, PlantDirectory, PlantModerationDecided), README.
- Ветка `feat/iteration-5-tournaments` слита в `main` локально; push — только по явной команде.

## Самопроверка плана (выполнена при написании)

- Сигнатуры портов in (Tasks 4–5) соответствуют вызовам контроллеров (Task 7); фильтр статуса списка — строка опубликованного языка: `adapter.in.web` не зависит от `domain` (LayerRules), разбор — `TournamentQueryService.parseStatus` → `UnknownStatusFilterException` (400).
- ACL-трансляции (Task 7) покрывают все исключения `PlantEligibilityService`: `PlantNotEligible` → `PlantNotReservable` (retryAt = restrictedUntil), `ReservationConflict` → `ImageAlreadyReserved`, `PlantNotFound` → `InvitedPlantNotFound`.
- `PlantModerationDecidedHandler` — синхронный `@EventListener` в tx `recordDecision` (паттерн `PlantSubmittedHandler` итерации 4, раздел 10.3, ADR-010).
- Планировщик уже включён (`ModerationWiringConfig`, `@EnableScheduling`) — нового wiring-config не нужно; poller, demo-ручка и ручка организатора делят `StartTournamentUseCase`.
- Контрактные базы репозиториев (Task 6) несут `@Transactional` на абстрактном классе — урок итерации 2 (фикс 56e8e3a).
- Свежий idempotency key на каждое принятие (дизайн, решение 3); повтор accept с тем же plantId возвращает текущий статус без нового резерва (`InvitationService.accept`).
- `TournamentsApiIT` не зависит от порядка тестов: уникальные email/названия, собственные данные; контрактные IT чистят схемы в `@BeforeEach`.
- Enum'ы схемы — VARCHAR + CHECK с явным маппингом (без EnumType.STRING), `@Version` только у Tournament/Invitation (Task 6, конвенции итераций 1–4).



