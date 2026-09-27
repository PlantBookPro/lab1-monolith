# Итерация 7 (глобальный турнир и geo) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Постоянный глобальный турнир (раздел 8): контекст `geo` (geohash-кластеризация, `ClusterSnapshot`), агрегаты `QualificationEpoch` + глобальные статусы `TournamentEntry` + scope окон `VotingWindow` (QUALIFICATION/FINAL), один use case `AdvanceGlobalCompetition` с фиксированным порядком одновременных границ и идемпотентностью (рестарт без повторной гибели), COOLDOWN 24 ч при глобальном поражении, REST `/global*`, приёмочный `GlobalApiIT` и `GlobalIdempotencyIT`, docs (ADR-012, глоссарий, aggregates, context map, README).

**Architecture:** Модульный монолит, bounded contexts `geo` (новый код) и `tournaments` (расширение) — geo Upstream для tournaments (раздел 4.3): координаты приходят во входной команде, identity читает только tournaments через ACL `ParticipantLocationsGateway` над расширенным `identity.api.UserDirectory`. `GlobalCompetition` — единственная запись `tournament` типа GLOBAL (фиксированный UUID, bootstrap при старте, `finish()` запрещён доменом); тайминги — `GlobalCompetitionSettings` (`plantarena.global.*`), точность geohash — `plantarena.geo.geohash-precision`. Границы времени — один use case `advance(now)`: закрыть квалификацию → закрыть финал → открыть следующий финал → открыть следующую эпоху (алгоритм 6); каждое окно — короткая tx (ADR-011), идемпотентность по статусам. Дизайн: `docs/specs/2026-09-27-iteration-7-global-design.md`.

**Tech Stack:** Java 21, Spring Boot 4.0.8 (MVC, Data JPA, `@EnableScheduling` — включён), PostgreSQL 17.5 + Flyway по контекстам (ADR-003; новые: `tournaments/V4__global.sql`, `geo/V2__clustering.sql`), Testcontainers 2.0.5, ArchUnit 1.5.0, JaCoCo 0.8.15, Awaitility (test).

## Global Constraints

- Ветка `feat/iteration-7-global` (от `main`); Conventional Commits; тесты коммитируются вместе с реализацией или раньше; красные тесты в `main` не попадают.
- TDD (раздел 14): сначала красный приёмочный `GlobalApiIT` (Task 1), затем внутренний цикл red→green→refactor.
- `./mvnw verify` — единственная команда проверки; совокупный LINE coverage ≥ 70% (gate); каждый коммит задачи — зелёный verify (порядок задач: домен → persistence → application → in-адаптеры; @Service-бины появляются только после JPA-реализаций портов — дизайн, решение 12).
- Все ID — UUID; время — `Instant`/UTC из внедрённого `Clock`; домен получает `Instant now` аргументом, не `Instant.now()`.
- Enum — VARCHAR + CHECK в миграциях, маппинг явный (`.name()`), без `EnumType.STRING`; Hibernate `ddl-auto=validate`; Flyway — единственный источник структуры.
- Границы контекстов — только через `api` и только из адаптеров (ArchUnit; направление tournaments → geo уже разрешено `ContextBoundaryTest`). `tournaments.domain`/`application` и `geo.domain`/`application` не импортируют чужие контексты.
- Отступление «одна tx — один агрегат» (раздел 12.3): закрытие глобальных окон и открытие эпохи/финала — ADR-012 (как ADR-011, план saga лабы №2); подача глобальной заявки (entry + резерв plants) — по образцу ADR-010.
- **Урок итерации 2:** абстрактные базовые классы контрактных тестов репозиториев несут `@Transactional` на себе.
- Новые понятия — сначала в `docs/domain/glossary.md` (Квалификационное окно, Финальное окно, Продвижение/PROMOTED, Очередь глобального турнира/QUEUED, Снятие заявки/WITHDRAWN), изменения правил — сначала docs (aggregates, ADR-012), затем код (Task 9 замыкает итерацию).
- REST (раздел 13): `POST /global/entries` — 201 + Location; `DELETE /global/entries/{id}` — 204; конфликты — 409 (`PLANT_NOT_APPROVED`, `LOCATION_REQUIRED`, `GLOBAL_ENTRY_ACTIVE`, `ENTRY_IN_WINDOW`), скрытое — 404 (`PLANT_NOT_FOUND`, `GLOBAL_ENTRY_NOT_FOUND`, `CLUSTER_NOT_FOUND`), неизвестный scope — 400 (`GLOBAL_SCOPE_UNKNOWN`); page/size 1–50 по умолчанию 20, `X-Total-Count`; публичные ручки (`GET /global`, `/global/clusters`, `/global/leaderboard`, `/global/clusters/{id}/leaderboard`) работают без идентификации; уникальные operationId с префиксом `global-`.
- Среда: macOS/zsh; рабочая директория `lab1-monolith/`; scratch-файлы диагностики не коммитировать.

## Карта файлов итерации

```text
src/main/java/com/plantarena/geo/                                НОВЫЙ КОНТЕКСТ (код)
├── api/ClusterAssignment.java                                   НОВОЕ (Task 3)
├── domain/
│   ├── Geohash.java                                             НОВОЕ (Task 2)
│   ├── ClusterMember.java                                       НОВОЕ (Task 2)
│   ├── ClusteringPolicy.java                                    НОВОЕ (Task 2)
│   ├── GeohashClusteringPolicy.java                             НОВОЕ (Task 2)
│   ├── ClusterSnapshot.java                                     НОВОЕ (Task 2)
│   └── ClusterSnapshotRepository.java                           НОВОЕ (Task 2)
├── application/
│   ├── ClusterAssignmentFacade.java                             НОВОЕ (Task 3)
│   └── GeoClusteringSettings.java                               НОВОЕ (Task 3)
└── adapter/out/persistence/
    ├── ClusterSnapshotJpaEntity.java                            НОВОЕ (Task 3)
    ├── ClusterSnapshotJpaRepository.java                        НОВОЕ (Task 3)
    └── JpaClusterSnapshotRepository.java                        НОВОЕ (Task 3)

src/main/java/com/plantarena/identity/
├── api/UserDirectory.java                                       ИЗМЕНЕНО: findLocation (Task 6)
└── application/UserDirectoryFacade.java                         ИЗМЕНЕНО (Task 6)

src/main/java/com/plantarena/tournaments/
├── domain/
│   ├── WindowScope.java                                         НОВОЕ (Task 4)
│   ├── EpochStatus.java                                         НОВОЕ (Task 4)
│   ├── QualificationEpoch.java                                  НОВОЕ (Task 4)
│   ├── QualificationEpochRepository.java                        НОВОЕ (Task 4)
│   ├── EntryStatus.java                                         ИЗМЕНЕНО: глобальные статусы (Task 4)
│   ├── TournamentType.java                                      ИЗМЕНЕНО: GLOBAL (Task 4)
│   ├── ParticipantResult.java                                   ИЗМЕНЕНО: PROMOTED (Task 4)
│   ├── Tournament.java                                          ИЗМЕНЕНО: global() + запрет finish (Task 4)
│   ├── TournamentEntry.java                                     ИЗМЕНЕНО: глобальные переходы (Task 4)
│   ├── WindowParticipant.java                                   ИЗМЕНЕНО: promote() (Task 4)
│   ├── VotingWindow.java                                        ИЗМЕНЕНО: scope + глобальные окна (Task 4)
│   ├── VotingWindowRepository.java                              ИЗМЕНЕНО: scope-методы (Task 5)
│   └── TournamentEntryRepository.java                           ИЗМЕНЕНО: глобальные запросы (Task 5)
├── application/
│   ├── GlobalCompetitionId.java                                 НОВОЕ (Task 6)
│   ├── GlobalCompetitionSettings.java                           НОВОЕ (Task 6)
│   ├── SubmitGlobalEntryService.java                            НОВОЕ (Task 6)
│   ├── WithdrawGlobalEntryService.java                          НОВОЕ (Task 6)
│   ├── MyGlobalEntryService — в GlobalQueryService              НОВОЕ (Task 6)
│   ├── AdvanceGlobalCompetitionService.java                     НОВОЕ (Task 6)
│   ├── GlobalQueryService.java                                  НОВОЕ (Task 6)
│   ├── EnsureGlobalCompetitionService.java                      НОВОЕ (Task 6)
│   ├── TournamentQueryService.java                              ИЗМЕНЕНО: скрыть GLOBAL (Task 5)
│   ├── CloseVotingWindowService.java                            ИЗМЕНЕНО: только PRIVATE (Task 5)
│   ├── VotingService.java                                       ИЗМЕНЕНО: права по scope (Task 7)
│   ├── SubmittedPlantNotFoundException.java                     НОВОЕ (Task 6)
│   ├── PlantNotApprovedException.java                           НОВОЕ (Task 6)
│   ├── LocationRequiredException.java                           НОВОЕ (Task 6)
│   ├── ActiveGlobalEntryExistsException.java                    НОВОЕ (Task 6)
│   ├── GlobalEntryNotFoundException.java                        НОВОЕ (Task 6)
│   ├── GlobalEntryNotWithdrawableException.java                 НОВОЕ (Task 6)
│   ├── ClusterNotFoundException.java                            НОВОЕ (Task 6)
│   ├── UnknownGlobalScopeException.java                         НОВОЕ (Task 6)
│   ├── port/in/{SubmitGlobalEntryUseCase, WithdrawGlobalEntryUseCase,
│   │   GetMyGlobalEntryUseCase, AdvanceGlobalCompetitionUseCase,
│   │   EnsureGlobalCompetitionUseCase, GetGlobalInfoUseCase,
│   │   ListGlobalClustersUseCase, GetGlobalLeaderboardUseCase}.java  НОВОЕ (Task 6)
│   └── port/out/
│       ├── ParticipantLocationsGateway.java                     НОВОЕ (Task 1)
│       └── ClusteringGateway.java                               НОВОЕ (Task 1)
└── adapter/
    ├── in/web/
    │   ├── GlobalController.java                                НОВОЕ (Task 7)
    │   ├── SubmitGlobalEntryRequest.java, GlobalEntryResponse.java,
    │   │   GlobalInfoResponse.java, GlobalClusterResponse.java,
    │   │   GlobalLeaderboardResponse.java                       НОВОЕ (Task 7)
    │   ├── DemoJobsController.java                              ИЗМЕНЕНО (Task 7)
    │   └── TournamentsExceptionHandler.java                     ИЗМЕНЕНО (Task 7)
    ├── in/jobs/
    │   ├── GlobalBoundaryPoller.java                            НОВОЕ (Task 7)
    │   └── GlobalCompetitionBootstrap.java                      НОВОЕ (Task 7)
    ├── out/identity/InProcessParticipantLocations.java          НОВОЕ (Task 6)
    ├── out/geo/InProcessClusteringGateway.java                  НОВОЕ (Task 6)
    └── out/persistence/
        ├── QualificationEpochJpaEntity.java                     НОВОЕ (Task 5)
        ├── QualificationEpochJpaRepository.java                 НОВОЕ (Task 5)
        ├── JpaQualificationEpochRepository.java                 НОВОЕ (Task 5)
        ├── VotingWindowJpaEntity.java                           ИЗМЕНЕНО (Task 5)
        ├── VotingWindowJpaRepository.java                       ИЗМЕНЕНО (Task 5)
        ├── JpaVotingWindowRepository.java                       ИЗМЕНЕНО (Task 4 — restore, Task 5 — mapping)
        ├── TournamentEntryJpaRepository.java                    ИЗМЕНЕНО (Task 5)
        ├── JpaTournamentEntryRepository.java                    ИЗМЕНЕНО (Task 5)
        ├── TournamentJpaRepository.java                         ИЗМЕНЕНО: только PRIVATE (Task 5)
        └── JpaTournamentRepository.java                         ИЗМЕНЕНО (Task 5)

src/main/java/com/plantarena/config/
├── GeoWiringConfig.java                                         НОВОЕ (Task 3)
└── TournamentsWiringConfig.java                                 НОВОЕ (Task 6)

src/main/resources/db/migration/
├── geo/V2__clustering.sql                                       НОВОЕ (Task 3)
└── tournaments/V4__global.sql                                   НОВОЕ (Task 5)

src/test/java/com/plantarena/
├── geo/
│   ├── domain/{GeohashTest, GeohashClusteringPolicyTest, ClusterSnapshotTest}.java  НОВОЕ (Task 2)
│   ├── application/ClusterAssignmentFacadeTest.java            НОВОЕ (Task 3)
│   ├── ClusterSnapshotRepositoryContractTest.java              НОВОЕ (Task 3)
│   └── adapter/out/persistence/JpaClusterSnapshotRepositoryContractIT.java  НОВОЕ (Task 3)
├── tournaments/
│   ├── GlobalApiIT.java                                         НОВОЕ (Task 1 — красный, Task 7 — зелёный)
│   ├── GlobalIdempotencyIT.java                                НОВОЕ (Task 8)
│   ├── domain/
│   │   ├── QualificationEpochTest.java                         НОВОЕ (Task 4)
│   │   ├── TournamentEntryGlobalTest.java                      НОВОЕ (Task 4)
│   │   ├── VotingWindowGlobalTest.java                         НОВОЕ (Task 4)
│   │   └── TournamentTest.java                                  ИЗМЕНЕНО: GLOBAL (Task 4)
│   ├── application/
│   │   ├── SubmitGlobalEntryServiceTest.java                   НОВОЕ (Task 6)
│   │   ├── WithdrawGlobalEntryServiceTest.java                 НОВОЕ (Task 6)
│   │   ├── AdvanceGlobalCompetitionServiceTest.java            НОВОЕ (Task 6)
│   │   ├── GlobalQueryServiceTest.java                         НОВОЕ (Task 6)
│   │   ├── TournamentQueryServiceTest.java                     ИЗМЕНЕНО (Task 5)
│   │   └── CloseVotingWindowServiceTest.java                   ИЗМЕНЕНО (Task 5)
│   └── application/support/
│       ├── FakeParticipantLocationsGateway.java                НОВОЕ (Task 6)
│       ├── FakeClusteringGateway.java                          НОВОЕ (Task 6)
│       ├── InMemoryQualificationEpochRepository.java           НОВОЕ (Task 6)
│       ├── InMemoryQualificationEpochRepositoryContractTest.java  НОВОЕ (Task 6)
│       ├── InMemoryVotingWindowRepository.java                 ИЗМЕНЕНО (Task 5)
│       └── InMemoryTournamentEntryRepository.java              ИЗМЕНЕНО (Task 5)
├── tournaments/VotingWindowRepositoryContractTest.java         ИЗМЕНЕНО (Task 5)
├── tournaments/TournamentEntryRepositoryContractTest.java      ИЗМЕНЕНО (Task 5)
├── tournaments/adapter/out/persistence/
│   ├── JpaVotingWindowRepositoryContractIT.java                ИЗМЕНЕНО (Task 5)
│   ├── JpaTournamentEntryRepositoryContractIT.java             ИЗМЕНЕНО (Task 5)
│   └── JpaQualificationEpochRepositoryContractIT.java          НОВОЕ (Task 6)
└── identity/application/UserDirectoryFacadeTest.java           ИЗМЕНЕНО: findLocation (Task 6)

docs/domain/adr/ADR-012-global-competition.md                   НОВОЕ (Task 9)
docs/domain/{glossary,aggregates,context-map}.md, README.md     ИЗМЕНЕНО (Task 9)
docs/specs/2026-09-27-iteration-7-global-design.md              СОЗДАН до плана
```

---

### Task 1: Порты out `ParticipantLocationsGateway`/`ClusteringGateway`, красный приёмочный `GlobalApiIT`

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/application/port/out/ParticipantLocationsGateway.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/out/ClusteringGateway.java`
- Test: `src/test/java/com/plantarena/tournaments/GlobalApiIT.java`

**Interfaces:**
- Consumes: REST итераций 1–6, `AbstractIntegrationTest`, эталонный `green-8x8.png`, `DeterministicPlantClassifier` (существует в `moderation.support`), `PUT /api/v1/me/location` (identity, итерация 1).
- Produces (для Tasks 6–7): порт `ParticipantLocationsGateway { Optional<UserLocation> findLocation(UUID userId); record UserLocation(double latitude, double longitude, long locationVersion) }`; порт `ClusteringGateway { List<AssignedCluster> assignClusters(UUID epochId, List<MemberLocation> members); record MemberLocation(UUID entryId, UUID userId, double latitude, double longitude, long locationVersion); record AssignedCluster(UUID clusterId, String clusterKey, List<UUID> entryIds) }`; красный `GlobalApiIT` (зелёный в Task 7).

- [ ] **Step 1: Порты out**

`src/main/java/com/plantarena/tournaments/application/port/out/ParticipantLocationsGateway.java`:

```java
package com.plantarena.tournaments.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Выходной порт tournaments: координаты пользователя для гео-кластеризации
 * (раздел 8). Адаптер — ACL над identity.api.UserDirectory (OHS, раздел 4.3);
 * наружу точные координаты не публикуются — только внутренним потребителям.
 */
public interface ParticipantLocationsGateway {

    /** Координаты профиля (null-профиля нет — Optional пуст). */
    Optional<UserLocation> findLocation(UUID userId);

    /** Координаты и версия профиля на момент чтения (locationVersion). */
    record UserLocation(double latitude, double longitude, long locationVersion) {
    }
}
```

`src/main/java/com/plantarena/tournaments/application/port/out/ClusteringGateway.java`:

```java
package com.plantarena.tournaments.application.port.out;

import java.util.List;
import java.util.UUID;

/**
 * Выходной порт tournaments: команда гео-кластеризации эпохи (раздел 8).
 * Адаптер — ACL над geo.api.ClusterAssignment: geo получает координаты во
 * входной команде, сам identity не читает (раздел 4.3). Возвращает
 * зафиксированные кластеры (snapshotId + ключ ячейки + состав).
 */
public interface ClusteringGateway {

    /**
     * Зафиксировать кластеры эпохи: группировка по ячейке geohash, снимки
     * состава неизменны (раздел 8, алгоритм 2).
     */
    List<AssignedCluster> assignClusters(UUID epochId, List<MemberLocation> members);

    /** Участник кластеризации: entry + владелец + координаты на момент эпохи. */
    record MemberLocation(UUID entryId, UUID userId, double latitude, double longitude,
                          long locationVersion) {
    }

    /** Зафиксированный кластер: snapshotId (идентификатор наружу), ключ, состав. */
    record AssignedCluster(UUID clusterId, String clusterKey, List<UUID> entryIds) {
    }
}
```

- [ ] **Step 2: Красный приёмочный `GlobalApiIT`**

`src/test/java/com/plantarena/tournaments/GlobalApiIT.java`:

```java
package com.plantarena.tournaments;

import com.jayway.jsonpath.JsonPath;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.support.DeterministicPlantClassifier;
import com.plantarena.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Внешний цикл TDD итерации 7: раздел 8 (алгоритмы 1–9) через HTTP на
 * Testcontainers. Классификатор детерминированный (зелёный PNG → APPROVED).
 * Тайминги глобального режима — секунды (TestPropertySource); границы
 * продвигает demo-ручка run-due (тот же use case, что scheduler) и scheduler.
 * u1/u2 — одна ячейка geohash (точные координаты совпадают — гарантированно
 * один кластер), u3 — другая ячейка. Красный до Task 7 (ручек /global нет).
 */
@TestPropertySource(properties = {
    "plantarena.global.epoch-duration=PT3S",
    "plantarena.global.final-window-duration=PT3S",
    "plantarena.geo.geohash-precision=4"
})
@DisplayName("Сценарии раздела 8: очередь → эпоха/кластеры → квалификация → финал")
class GlobalApiIT extends AbstractIntegrationTest {

    private static final String DEMO_HEADER = "X-Demo-User-Id";
    private static final double LAT_MOSCOW = 55.7558;
    private static final double LON_MOSCOW = 37.6173;
    private static final double LAT_SPB = 59.9375;
    private static final double LON_SPB = 30.3086;

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
    @DisplayName("полный цикл: заявки → эпоха с кластерами → квалификация (top-1, гибель+COOLDOWN) → финал (floor(n/2), лидер)")
    void полный_цикл_глобального_турнира() throws Exception {
        UUID u1 = createUserAsAdmin("g-u1@example.com", "G1");
        UUID u2 = createUserAsAdmin("g-u2@example.com", "G2");
        UUID u3 = createUserAsAdmin("g-u3@example.com", "G3");
        UUID u4 = createUserAsAdmin("g-u4@example.com", "G4");
        UUID stranger = createUserAsAdmin("g-stranger@example.com", "Stranger");

        setLocation(u1, LAT_MOSCOW, LON_MOSCOW);
        setLocation(u2, LAT_MOSCOW, LON_MOSCOW); // та же ячейка, что у u1
        setLocation(u3, LAT_SPB, LON_SPB);       // другая ячейка
        setLocation(u4, LAT_MOSCOW, LON_MOSCOW);

        UUID plant1 = approvedPlantOf(u1, "Фикус u1");
        UUID plant2 = approvedPlantOf(u2, "Фикус u2");
        UUID plant3 = approvedPlantOf(u3, "Фикус u3");
        UUID plant4 = approvedPlantOf(u4, "Фикус u4");

        // алгоритм 1: одобренная заявка → очередь, картинка резервируется
        UUID entry1 = submitGlobalEntry(u1, plant1);
        UUID entry2 = submitGlobalEntry(u2, plant2);
        UUID entry3 = submitGlobalEntry(u3, plant3);
        UUID entry4 = submitGlobalEntry(u4, plant4);
        myGlobalEntry(u1, "QUEUED");

        // снятие из очереди (раздел 13): только QUEUED, резерв освобождается
        mockMvc.perform(delete("/api/v1/global/entries/" + entry4)
                .header(DEMO_HEADER, u4.toString()))
            .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/global/entries/" + entry4)
                .header(DEMO_HEADER, u4.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ENTRY_IN_WINDOW"));

        // негативные подачи: активное участие, нет координат, не одобрено, гость
        mockMvc.perform(post("/api/v1/global/entries")
                .header(DEMO_HEADER, u1.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plant1)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("GLOBAL_ENTRY_ACTIVE"));
        mockMvc.perform(post("/api/v1/global/entries")
                .header(DEMO_HEADER, stranger.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plant1)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LOCATION_REQUIRED"));
        UUID pendingPlant = pendingPlantOf(u4);
        mockMvc.perform(post("/api/v1/global/entries")
                .header(DEMO_HEADER, u4.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(pendingPlant)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PLANT_NOT_APPROVED"));
        mockMvc.perform(post("/api/v1/global/entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plant1)))
            .andExpect(status().isUnauthorized());

        // алгоритм 2: открытие эпохи фиксирует состав и кластеры
        runDue();
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> mockMvc.perform(get("/api/v1/global/clusters"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2")));
        String clusters = mockMvc.perform(get("/api/v1/global/clusters"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<?> moscow = JsonPath.read(clusters,
            "$[?(@.memberCount == 2)]"); // u1 + u2 в одной ячейке
        assertThat(moscow).hasSize(1);
        UUID moscowClusterId = UUID.fromString(
            (String) ((java.util.Map<String, Object>) moscow.get(0)).get("clusterId"));
        UUID moscowWindowId = UUID.fromString(
            (String) ((java.util.Map<String, Object>) moscow.get(0)).get("windowId"));
        myGlobalEntry(u1, "QUALIFYING");

        // раздел 2: в глобальных окнах голосует любой идентифицированный
        mockMvc.perform(put("/api/v1/windows/" + moscowWindowId + "/entries/" + entry1 + "/vote")
                .header(DEMO_HEADER, stranger.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.score").value(1));
        mockMvc.perform(put("/api/v1/windows/" + moscowWindowId + "/entries/" + entry2 + "/vote")
                .header(DEMO_HEADER, u1.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.score").value(1));
        // самоголосование — 403 (допущение 6); гость — 401 (до итерации 8)
        mockMvc.perform(put("/api/v1/windows/" + moscowWindowId + "/entries/" + entry1 + "/vote")
                .header(DEMO_HEADER, u1.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/windows/" + moscowWindowId + "/entries/" + entry1 + "/vote")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isUnauthorized());

        // алгоритм 9: лидерборд кластера — scope/windowId/closesAt/asOf
        mockMvc.perform(get("/api/v1/global/clusters/" + moscowClusterId + "/leaderboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("QUALIFICATION"))
            .andExpect(jsonPath("$.windowId").value(moscowWindowId.toString()))
            .andExpect(jsonPath("$.closesAt").exists())
            .andExpect(jsonPath("$.asOf").exists())
            .andExpect(jsonPath("$.items.length()").value(2));

        // алгоритмы 3–5: закрытие квалификации — top-1 в финал, остальные гибнут
        // с COOLDOWN 24 ч (допущение 2); u3 — единственный в ячейке, проходит
        // без голосов (алгоритм 4)
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> myGlobalEntry(u1, "FINALIST"));
        mockMvc.perform(get("/api/v1/plants/" + plant2)
                .header(DEMO_HEADER, u2.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lifeStatus").value("DEAD"));
        // суточный запрет совпавшей картинки — retryAt (допущения 2–3)
        UUID sameImageAsset = uploadAs(u2, referenceBytes("green-8x8.png"));
        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, u2.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":\"%s\",\"title\":\"Повтор u2\"}".formatted(sameImageAsset)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IMAGE_RESTRICTED"))
            .andExpect(jsonPath("$.retryAt").exists());

        // алгоритм 6: финал открывается вместе с выжившими; u1 и u3 — финалисты
        mockMvc.perform(get("/api/v1/global/leaderboard?scope=FINAL"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("FINAL"))
            .andExpect(jsonPath("$.windowId").exists())
            .andExpect(jsonPath("$.items.length()").value(2));

        // голос в финале: u1 получает LIKE, u3 — ноль
        mockMvc.perform(put("/api/v1/windows/" + finalWindowId() + "/entries/" + entry1 + "/vote")
                .header(DEMO_HEADER, stranger.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.score").value(1));

        // алгоритм 7: n=2 → выбывает max(1, floor(2/2)) = 1 худший (u3);
        // выживший u1 — лидер, следующий финал с одним участником
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> mockMvc.perform(get("/api/v1/global/leaderboard?scope=FINAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)));
        mockMvc.perform(get("/api/v1/plants/" + plant3)
                .header(DEMO_HEADER, u3.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lifeStatus").value("DEAD"));
        myGlobalEntry(u1, "FINALIST"); // лидер остаётся FINALIST (алгоритм 7)
        mockMvc.perform(get("/api/v1/plants/" + plant1)
                .header(DEMO_HEADER, u1.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lifeStatus").value("ALIVE"));

        // GET /global — публично: тайминги, текущее финальное окно
        mockMvc.perform(get("/api/v1/global"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.epochDurationSeconds").value(3))
            .andExpect(jsonPath("$.finalWindowDurationSeconds").value(3))
            .andExpect(jsonPath("$.currentFinalWindow.id").exists());

        // идемпотентность: повторные проходы ничего не меняют (подробно — GlobalIdempotencyIT)
        String before = mockMvc.perform(get("/api/v1/global/leaderboard?scope=FINAL"))
            .andReturn().getResponse().getContentAsString();
        runDue();
        runDue();
        String after = mockMvc.perform(get("/api/v1/global/leaderboard?scope=FINAL"))
            .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.read(after, "$.windowId"))
            .isEqualTo(JsonPath.read(before, "$.windowId"));
    }

    @Test
    @DisplayName("ошибки: неизвестный scope 400, неизвестный кластер 404, чужая заявка 404, гость 401")
    void ошибки_глобальных_ручек() throws Exception {
        UUID u1 = createUserAsAdmin("g-err@example.com", "Err");
        setLocation(u1, LAT_MOSCOW, LON_MOSCOW);
        UUID plant = approvedPlantOf(u1, "Фикус err");
        UUID entry = submitGlobalEntry(u1, plant);

        mockMvc.perform(get("/api/v1/global/leaderboard?scope=QUALIFICATION"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("GLOBAL_SCOPE_UNKNOWN"));
        mockMvc.perform(get("/api/v1/global/clusters/" + UUID.randomUUID() + "/leaderboard"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CLUSTER_NOT_FOUND"));
        mockMvc.perform(delete("/api/v1/global/entries/" + entry)
                .header(DEMO_HEADER, u1.toString()))
            .andExpect(status().isNoContent()); // своя QUEUED — можно
        mockMvc.perform(delete("/api/v1/global/entries/" + UUID.randomUUID())
                .header(DEMO_HEADER, u1.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("GLOBAL_ENTRY_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/global/entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plant)))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/global-entry"))
            .andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private void setLocation(UUID user, double latitude, double longitude) throws Exception {
        mockMvc.perform(put("/api/v1/me/location")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":%s,\"longitude\":%s}".formatted(latitude, longitude)))
            .andExpect(status().isOk());
    }

    private UUID submitGlobalEntry(UUID user, UUID plantId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/global/entries")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plantId)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void myGlobalEntry(UUID user, String expectedStatus) throws Exception {
        mockMvc.perform(get("/api/v1/me/global-entry")
                .header(DEMO_HEADER, user.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    private UUID finalWindowId() throws Exception {
        String body = mockMvc.perform(get("/api/v1/global/leaderboard?scope=FINAL"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.windowId"));
    }

    private void runDue() throws Exception {
        mockMvc.perform(post("/api/v1/internal/demo/jobs/run-due")
                .header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isOk());
    }

    private UUID approvedPlantOf(UUID user, String title) throws Exception {
        UUID plantId = createPlantOf(user, title);
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> mockMvc.perform(get("/api/v1/plants/" + plantId + "/moderation")
                    .header(DEMO_HEADER, user.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("APPROVED")));
        return plantId;
    }

    private UUID pendingPlantOf(UUID user) throws Exception {
        return createPlantOf(user, "На модерации");
    }

    private UUID createPlantOf(UUID user, String title) throws Exception {
        UUID assetId = uploadAs(user, referenceBytes("green-8x8.png"));
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

- [ ] **Step 3: Запустить — красный**

```bash
./mvnw -q verify -Dit.test=GlobalApiIT -DfailIfNoTests=false
```

Ожидание: unit-тесты PASS; `GlobalApiIT` FAIL: все сценарии падают на `POST /api/v1/global/entries` / `GET /api/v1/global` — 404 (ручек глобального турнира нет). Это честный красный внешнего цикла.

- [ ] **Step 4: Commit**

```bash
git checkout -b feat/iteration-7-global
git add docs/specs/2026-09-27-iteration-7-global-design.md \
  src/main/java/com/plantarena/tournaments/application/port/out/ParticipantLocationsGateway.java \
  src/main/java/com/plantarena/tournaments/application/port/out/ClusteringGateway.java \
  src/test/java/com/plantarena/tournaments/GlobalApiIT.java
git commit -m "feat(global): порты ParticipantLocationsGateway/ClusteringGateway, красный приёмочный GlobalApiIT"
```

---

### Task 2: geo домен — `Geohash`, `ClusteringPolicy`, `ClusterSnapshot`

**Files:**
- Create: `src/main/java/com/plantarena/geo/domain/Geohash.java`
- Create: `src/main/java/com/plantarena/geo/domain/ClusterMember.java`
- Create: `src/main/java/com/plantarena/geo/domain/ClusteringPolicy.java`
- Create: `src/main/java/com/plantarena/geo/domain/GeohashClusteringPolicy.java`
- Create: `src/main/java/com/plantarena/geo/domain/ClusterSnapshot.java`
- Create: `src/main/java/com/plantarena/geo/domain/ClusterSnapshotRepository.java`
- Test: `src/test/java/com/plantarena/geo/domain/GeohashTest.java`
- Test: `src/test/java/com/plantarena/geo/domain/GeohashClusteringPolicyTest.java`
- Test: `src/test/java/com/plantarena/geo/domain/ClusterSnapshotTest.java`

**Interfaces:**
- Consumes: ничего (чистый Java, без Spring/JPA — правило 10.2.5).
- Produces (для Task 3): `Geohash.encode(double latitude, double longitude, int precision) → String` (base32, 1–12); `ClusterMember(UUID entryId, UUID userId, double latitude, double longitude, long locationVersion)` с валидацией диапазонов; `ClusteringPolicy { String policyVersion(); Map<String, List<ClusterMember>> cluster(List<ClusterMember> members); }`, реализация `GeohashClusteringPolicy(int precision)` с `policyVersion() = "geohash-v1-p" + precision`; агрегат `ClusterSnapshot.fix(UUID epochId, String clusterKey, String policyVersion, List<ClusterMember> members, Instant now)` / `restore(...)` + `record SnapshotMember(UUID userId, UUID entryId, long locationVersion)`; порт `ClusterSnapshotRepository { save, findByEpochId, findById }`.

- [ ] **Step 1: Красные доменные тесты**

`src/test/java/com/plantarena/geo/domain/GeohashTest.java`:

```java
package com.plantarena.geo.domain;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Geohash (раздел 8): канонический алгоритм base32, эталонные векторы. */
@DisplayName("Geohash: кодирование координат в ячейку сетки")
class GeohashTest {

    static Stream<Arguments> vectors() {
        return Stream.of(
            Arguments.arguments(57.64911, 10.40744, 11, "u4pruydqqvj"),
            Arguments.arguments(-25.382708, -49.585507, 8, "6gkzwgjz"),
            Arguments.arguments(0.0, 0.0, 4, "s000"),
            Arguments.arguments(-90.0, -180.0, 4, "8000"),
            Arguments.arguments(90.0, 180.0, 4, "zzzz"));
    }

    @ParameterizedTest(name = "{0},{1} p={2} → {3}")
    @MethodSource("vectors")
    void эталонные_векторы(double latitude, double longitude, int precision, String expected) {
        assertThat(Geohash.encode(latitude, longitude, precision)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 13})
    void точность_вне_диапазона(int precision) {
        assertThatThrownBy(() -> Geohash.encode(10, 10, precision))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("координаты вне диапазона — ошибка (границы включаются)")
    void координаты_вне_диапазона() {
        assertThatThrownBy(() -> Geohash.encode(90.0001, 0, 4))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Geohash.encode(0, -180.0001, 4))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("префикс ячейки меньшей точности — ячейка большей точности (вложенность сетки)")
    void вложенность_точностей() {
        String coarse = Geohash.encode(55.7558, 37.6173, 3);
        String fine = Geohash.encode(55.7558, 37.6173, 6);
        assertThat(fine).startsWith(coarse);
    }
}
```

`src/test/java/com/plantarena/geo/domain/GeohashClusteringPolicyTest.java`:

```java
package com.plantarena.geo.domain;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Политика кластеризации (раздел 8): фиксированная geohash-сетка. */
@DisplayName("Geohash-кластеризация: группировка по ячейке, версия политики")
class GeohashClusteringPolicyTest {

    @Test
    @DisplayName("близкие точки в одной ячейке попадают в один кластер, далёкие — в разные")
    void группировка_по_ячейкам() {
        GeohashClusteringPolicy policy = new GeohashClusteringPolicy(4);
        ClusterMember moscow1 = member("e1", 55.7558, 37.6173);
        ClusterMember moscow2 = member("e2", 55.7558, 37.6173);
        ClusterMember spb = member("e3", 59.9375, 30.3086);
        Map<String, List<ClusterMember>> clusters = policy.cluster(List.of(moscow1, moscow2, spb));
        assertThat(clusters).hasSize(2);
        assertThat(clusters.values().stream().flatMap(List::stream))
            .containsExactlyInAnyOrder(moscow1, moscow2, spb);
        assertThat(clusters).hasEntrySatisfying(Geohash.encode(55.7558, 37.6173, 4),
            members -> assertThat(members).containsExactlyInAnyOrder(moscow1, moscow2));
    }

    @Test
    @DisplayName("версия политики фиксирует алгоритм и точность")
    void версия_политики() {
        assertThat(new GeohashClusteringPolicy(4).policyVersion()).isEqualTo("geohash-v1-p4");
    }

    @Test
    @DisplayName("точность вне 1–12 — ошибка конфигурации")
    void неверная_точность() {
        assertThatThrownBy(() -> new GeohashClusteringPolicy(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private ClusterMember member(String entrySuffix, double lat, double lon) {
        return new ClusterMember(java.util.UUID.nameUUIDFromBytes(entrySuffix.getBytes()),
            java.util.UUID.randomUUID(), lat, lon, 1L);
    }
}
```

`src/test/java/com/plantarena/geo/domain/ClusterSnapshotTest.java`:

```java
package com.plantarena.geo.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Снимок кластера (раздел 8, алгоритм 2): состав и версия политики неизменны. */
@DisplayName("ClusterSnapshot: фиксация состава эпохи")
class ClusterSnapshotTest {

    @Test
    @DisplayName("фиксация сохраняет ключ, версию политики и состав с locationVersion")
    void фиксация_состава() {
        UUID entryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ClusterMember member = new ClusterMember(entryId, userId, 55.7558, 37.6173, 7L);
        ClusterSnapshot snapshot = ClusterSnapshot.fix(UUID.randomUUID(), "u4pu",
            "geohash-v1-p4", List.of(member), Instant.parse("2026-09-27T10:00:00Z"));
        assertThat(snapshot.clusterKey()).isEqualTo("u4pu");
        assertThat(snapshot.policyVersion()).isEqualTo("geohash-v1-p4");
        assertThat(snapshot.members()).containsExactly(
            new ClusterSnapshot.SnapshotMember(userId, entryId, 7L));
    }

    @Test
    @DisplayName("пустой кластер не фиксируется")
    void пустой_кластер_запрещён() {
        assertThatThrownBy(() -> ClusterSnapshot.fix(UUID.randomUUID(), "u4pu",
            "geohash-v1-p4", List.of(), Instant.parse("2026-09-27T10:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Запустить — красный**

```bash
./mvnw -q test -Dtest='GeohashTest,GeohashClusteringPolicyTest,ClusterSnapshotTest'
```

Ожидание: FAIL (компиляция): `cannot find symbol: class Geohash` и т. д.

- [ ] **Step 3: Минимальная реализация домена geo**

`src/main/java/com/plantarena/geo/domain/Geohash.java`:

```java
package com.plantarena.geo.domain;

/**
 * Geohash (раздел 8): кодирование координат в ячейку фиксированной сетки.
 * Канонический алгоритм base32 (чередование битов долготы/широты, начиная
 * с долготы); точность 1–12 символов. Воспроизводимая учебная аппроксимация:
 * близкие точки по разные стороны границы ячейки попадают в разные группы.
 */
public final class Geohash {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    private Geohash() {
    }

    public static String encode(double latitude, double longitude, int precision) {
        if (precision < 1 || precision > 12) {
            throw new IllegalArgumentException("Точность geohash 1–12: " + precision);
        }
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Широта вне диапазона [-90, 90]: " + latitude);
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Долгота вне диапазона [-180, 180]: " + longitude);
        }
        double latMin = -90;
        double latMax = 90;
        double lonMin = -180;
        double lonMax = 180;
        StringBuilder hash = new StringBuilder(precision);
        boolean even = true;
        int bit = 0;
        int ch = 0;
        while (hash.length() < precision) {
            if (even) {
                double mid = (lonMin + lonMax) / 2;
                if (longitude >= mid) {
                    ch |= 1 << (4 - bit);
                    lonMin = mid;
                } else {
                    lonMax = mid;
                }
            } else {
                double mid = (latMin + latMax) / 2;
                if (latitude >= mid) {
                    ch |= 1 << (4 - bit);
                    latMin = mid;
                } else {
                    latMax = mid;
                }
            }
            even = !even;
            if (bit < 4) {
                bit++;
            } else {
                hash.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }
        return hash.toString();
    }
}
```

`src/main/java/com/plantarena/geo/domain/ClusterMember.java`:

```java
package com.plantarena.geo.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Участник кластеризации (раздел 8): глобальное участие + координаты
 * владельца на момент фиксации эпохи (locationVersion — версия профиля).
 */
public record ClusterMember(UUID entryId, UUID userId, double latitude, double longitude,
                            long locationVersion) {

    public ClusterMember {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(userId, "userId");
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Широта вне диапазона [-90, 90]: " + latitude);
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Долгота вне диапазона [-180, 180]: " + longitude);
        }
    }
}
```

`src/main/java/com/plantarena/geo/domain/ClusteringPolicy.java`:

```java
package com.plantarena.geo.domain;

import java.util.List;
import java.util.Map;

/**
 * Доменная политика кластеризации (раздел 8): географическая сетка с
 * фиксированной точностью за `ClusteringPolicy`; версия фиксируется в каждом
 * снимке. K-means/DBSCAN не требуются (учебная аппроксимация).
 */
public interface ClusteringPolicy {

    /** Версия политики: алгоритм + точность (фиксируется в ClusterSnapshot). */
    String policyVersion();

    /** Группировка участников по ячейкам: ключ ячейки → состав. */
    Map<String, List<ClusterMember>> cluster(List<ClusterMember> members);
}
```

`src/main/java/com/plantarena/geo/domain/GeohashClusteringPolicy.java`:

```java
package com.plantarena.geo.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Реализация политики: geohash-сетка выбранной точности (раздел 8). */
public final class GeohashClusteringPolicy implements ClusteringPolicy {

    private final int precision;

    public GeohashClusteringPolicy(int precision) {
        if (precision < 1 || precision > 12) {
            throw new IllegalArgumentException("Точность geohash 1–12: " + precision);
        }
        this.precision = precision;
    }

    @Override
    public String policyVersion() {
        return "geohash-v1-p" + precision;
    }

    @Override
    public Map<String, List<ClusterMember>> cluster(List<ClusterMember> members) {
        return members.stream().collect(Collectors.groupingBy(
            member -> Geohash.encode(member.latitude(), member.longitude(), precision),
            TreeMap::new, Collectors.toList()));
    }
}
```

`src/main/java/com/plantarena/geo/domain/ClusterSnapshot.java`:

```java
package com.plantarena.geo.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат geo (раздел 8, алгоритм 2): зафиксированный кластер эпохи. Состав и
 * версия политики неизменны после фиксации; изменяется только созданием.
 */
public final class ClusterSnapshot {

    private final UUID id;
    private final UUID epochId;
    private final String clusterKey;
    private final String policyVersion;
    private final List<SnapshotMember> members;
    private final Instant createdAt;

    private ClusterSnapshot(UUID id, UUID epochId, String clusterKey, String policyVersion,
                            List<SnapshotMember> members, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.epochId = Objects.requireNonNull(epochId, "epochId");
        this.clusterKey = Objects.requireNonNull(clusterKey, "clusterKey");
        this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
        this.members = List.copyOf(members);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Фиксация кластера при открытии эпохи (минимум один участник). */
    public static ClusterSnapshot fix(UUID epochId, String clusterKey, String policyVersion,
                                      List<ClusterMember> members, Instant now) {
        Objects.requireNonNull(members, "members");
        if (members.isEmpty()) {
            throw new IllegalArgumentException("Кластер фиксируется минимум с одним участником");
        }
        List<SnapshotMember> snapshotMembers = members.stream()
            .map(member -> new SnapshotMember(member.userId(), member.entryId(),
                member.locationVersion()))
            .toList();
        return new ClusterSnapshot(UUID.randomUUID(), epochId, clusterKey, policyVersion,
            snapshotMembers, now);
    }

    /** Восстановление из хранилища (использует только persistence-адаптер). */
    public static ClusterSnapshot restore(UUID id, UUID epochId, String clusterKey,
                                          String policyVersion,
                                          List<SnapshotMember> members, Instant createdAt) {
        return new ClusterSnapshot(id, epochId, clusterKey, policyVersion, members, createdAt);
    }

    public UUID id() {
        return id;
    }

    public UUID epochId() {
        return epochId;
    }

    public String clusterKey() {
        return clusterKey;
    }

    public String policyVersion() {
        return policyVersion;
    }

    public List<SnapshotMember> members() {
        return members;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Состав кластера: пользователь, участие, версия координат (раздел 11). */
    public record SnapshotMember(UUID userId, UUID entryId, long locationVersion) {
    }
}
```

`src/main/java/com/plantarena/geo/domain/ClusterSnapshotRepository.java`:

```java
package com.plantarena.geo.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Порт репозитория агрегата ClusterSnapshot. */
public interface ClusterSnapshotRepository {

    ClusterSnapshot save(ClusterSnapshot snapshot);

    List<ClusterSnapshot> findByEpochId(UUID epochId);

    Optional<ClusterSnapshot> findById(UUID id);
}
```

- [ ] **Step 4: Запустить — зелёный**

```bash
./mvnw -q test -Dtest='GeohashTest,GeohashClusteringPolicyTest,ClusterSnapshotTest'
```

Ожидание: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/plantarena/geo src/test/java/com/plantarena/geo
git commit -m "feat(geo): домен — Geohash, ClusteringPolicy, ClusterSnapshot"
```

---

### Task 3: geo application/api/persistence — фасад `ClusterAssignment`, миграция V2, JPA

**Files:**
- Create: `src/main/java/com/plantarena/geo/api/ClusterAssignment.java`
- Create: `src/main/java/com/plantarena/geo/application/ClusterAssignmentFacade.java`
- Create: `src/main/java/com/plantarena/geo/application/GeoClusteringSettings.java`
- Create: `src/main/java/com/plantarena/geo/adapter/out/persistence/ClusterSnapshotJpaEntity.java`
- Create: `src/main/java/com/plantarena/geo/adapter/out/persistence/ClusterSnapshotJpaRepository.java`
- Create: `src/main/java/com/plantarena/geo/adapter/out/persistence/JpaClusterSnapshotRepository.java`
- Create: `src/main/java/com/plantarena/config/GeoWiringConfig.java`
- Create: `src/main/resources/db/migration/geo/V2__clustering.sql`
- Test: `src/test/java/com/plantarena/geo/application/ClusterAssignmentFacadeTest.java`
- Test: `src/test/java/com/plantarena/geo/ClusterSnapshotRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/geo/adapter/out/persistence/JpaClusterSnapshotRepositoryContractIT.java`
- Test: `src/test/java/com/plantarena/geo/application/support/InMemoryClusterSnapshotRepository.java`
- Test: `src/test/java/com/plantarena/geo/application/support/InMemoryClusterSnapshotRepositoryContractTest.java`

**Interfaces:**
- Consumes: домен Task 2; `Clock`-бин (существует); `AbstractIntegrationTest`/`@DataJpaTest`-паттерн контрактных IT (по образцу `JpaVotingWindowRepositoryContractIT`).
- Produces (для Task 6): `geo.api.ClusterAssignment { List<AssignedCluster> assignClusters(UUID epochId, List<Member> members); record Member(UUID entryId, UUID userId, double latitude, double longitude, long locationVersion); record AssignedCluster(UUID snapshotId, String clusterKey, List<UUID> entryIds) }`; бин `ClusteringPolicy` (точность из `plantarena.geo.geohash-precision`, по умолчанию 4).

- [ ] **Step 1: Контракт `geo.api.ClusterAssignment` + настройки**

`src/main/java/com/plantarena/geo/api/ClusterAssignment.java`:

```java
package com.plantarena.geo.api;

import java.util.List;
import java.util.UUID;

/**
 * Опубликованный контракт geo (Upstream для tournaments, раздел 4.3):
 * команда фиксации кластеров эпохи. Координаты приходят во входной команде —
 * geo сам identity не читает. Возвращает зафиксированные снимки: snapshotId
 * (идентификатор кластера наружу), ключ ячейки и состав участий.
 */
public interface ClusterAssignment {

    List<AssignedCluster> assignClusters(UUID epochId, List<Member> members);

    /** Участник: entry + владелец + координаты на момент эпохи. */
    record Member(UUID entryId, UUID userId, double latitude, double longitude,
                  long locationVersion) {
    }

    /** Зафиксированный кластер эпохи. */
    record AssignedCluster(UUID snapshotId, String clusterKey, List<UUID> entryIds) {
    }
}
```

`src/main/java/com/plantarena/geo/application/GeoClusteringSettings.java`:

```java
package com.plantarena.geo.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки кластеризации (раздел 8): точность geohash-сетки, задаётся
 * конфигурацией и фиксируется в версии политики (1–12).
 */
@ConfigurationProperties(prefix = "plantarena.geo")
public record GeoClusteringSettings(int geohashPrecision) {

    public GeoClusteringSettings {
        if (geohashPrecision < 1 || geohashPrecision > 12) {
            throw new IllegalArgumentException(
                "plantarena.geo.geohash-precision вне диапазона 1–12: " + geohashPrecision);
        }
    }

    public GeoClusteringSettings {
    }
}
```

Внимание: рекорды с одним каноническим конструктором — оставить один compact-конструктор с валидацией (второй пустой не нужен):

```java
package com.plantarena.geo.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "plantarena.geo")
public record GeoClusteringSettings(int geohashPrecision) {

    public GeoClusteringSettings {
        if (geohashPrecision < 1 || geohashPrecision > 12) {
            throw new IllegalArgumentException(
                "plantarena.geo.geohash-precision вне диапазона 1–12: " + geohashPrecision);
        }
    }
}
```

и значение по умолчанию задать в `application.yml` (`plantarena.geo.geohash-precision: 4`) — если в проекте дефолты свойств задаются иначе (через `@DefaultValue`), следовать существующему паттерну.

`src/main/java/com/plantarena/config/GeoWiringConfig.java`:

```java
package com.plantarena.config;

import com.plantarena.geo.application.GeoClusteringSettings;
import com.plantarena.geo.domain.ClusteringPolicy;
import com.plantarena.geo.domain.GeohashClusteringPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Связывание контекста geo: политика кластеризации из конфигурации. */
@Configuration
@EnableConfigurationProperties(GeoClusteringSettings.class)
public class GeoWiringConfig {

    @Bean
    public ClusteringPolicy geohashClusteringPolicy(GeoClusteringSettings settings) {
        return new GeohashClusteringPolicy(settings.geohashPrecision());
    }
}
```

- [ ] **Step 2: Красные тесты фасада и контракта репозитория**

`src/test/java/com/plantarena/geo/application/ClusterAssignmentFacadeTest.java`:

```java
package com.plantarena.geo.application;

import com.plantarena.geo.api.ClusterAssignment;
import com.plantarena.geo.application.support.InMemoryClusterSnapshotRepository;
import com.plantarena.geo.domain.GeohashClusteringPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Фасад кластеризации: команда → снимки → ответ с составами. */
@DisplayName("ClusterAssignmentFacade: фиксация кластеров эпохи")
class ClusterAssignmentFacadeTest {

    private final InMemoryClusterSnapshotRepository snapshots = new InMemoryClusterSnapshotRepository();
    private final ClusterAssignment facade = new ClusterAssignmentFacade(
        new GeohashClusteringPolicy(4), snapshots,
        Clock.fixed(Instant.parse("2026-09-27T10:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("группирует по ячейкам, сохраняет снимки, возвращает snapshotId + ключ + состав")
    void фиксация_кластеров() {
        UUID epochId = UUID.randomUUID();
        UUID entry1 = UUID.randomUUID();
        UUID entry2 = UUID.randomUUID();
        UUID entry3 = UUID.randomUUID();
        List<ClusterAssignment.AssignedCluster> clusters = facade.assignClusters(epochId,
            List.of(
                new ClusterAssignment.Member(entry1, UUID.randomUUID(), 55.7558, 37.6173, 1L),
                new ClusterAssignment.Member(entry2, UUID.randomUUID(), 55.7558, 37.6173, 2L),
                new ClusterAssignment.Member(entry3, UUID.randomUUID(), 59.9375, 30.3086, 3L)));
        assertThat(clusters).hasSize(2);
        ClusterAssignment.AssignedCluster moscow = clusters.stream()
            .filter(cluster -> cluster.entryIds().size() == 2).findFirst().orElseThrow();
        assertThat(moscow.entryIds()).containsExactlyInAnyOrder(entry1, entry2);
        assertThat(moscow.clusterKey()).isEqualTo("u4pu");
        assertThat(snapshots.findByEpochId(epochId)).hasSize(2);
        assertThat(snapshots.findById(moscow.snapshotId())).isPresent();
    }
}
```

`src/test/java/com/plantarena/geo/ClusterSnapshotRepositoryContractTest.java`:

```java
package com.plantarena.geo;

import com.plantarena.geo.domain.ClusterMember;
import com.plantarena.geo.domain.ClusterSnapshot;
import com.plantarena.geo.domain.ClusterSnapshotRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контрактный тест репозитория ClusterSnapshot (раздел 14.2): фейк и
 * JPA-адаптер ведут себя одинаково. Наследники JPA — @Transactional на себе
 * (урок итерации 2).
 */
@DisplayName("Контракт ClusterSnapshotRepository")
public abstract class ClusterSnapshotRepositoryContractTest {

    protected abstract ClusterSnapshotRepository repository();

    @Test
    @DisplayName("save → findByEpochId возвращает снимки эпохи; findById — по id")
    void сохранение_и_чтение() {
        ClusterSnapshotRepository repository = repository();
        UUID epochId = UUID.randomUUID();
        ClusterSnapshot first = snapshot(epochId, "u4pu", "e-1");
        ClusterSnapshot second = snapshot(epochId, "u8t", "e-2");
        repository.save(first);
        repository.save(second);

        assertThat(repository.findByEpochId(epochId))
            .extracting(ClusterSnapshot::clusterKey)
            .containsExactlyInAnyOrder("u4pu", "u8t");
        ClusterSnapshot loaded = repository.findById(first.id()).orElseThrow();
        assertThat(loaded.policyVersion()).isEqualTo("geohash-v1-p4");
        assertThat(loaded.members()).isEqualTo(first.members());
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    private ClusterSnapshot snapshot(UUID epochId, String clusterKey, String entrySuffix) {
        UUID entryId = UUID.nameUUIDFromBytes(entrySuffix.getBytes());
        return ClusterSnapshot.fix(epochId, clusterKey, "geohash-v1-p4",
            List.of(new ClusterMember(entryId, UUID.randomUUID(), 55.7558, 37.6173, 1L)),
            Instant.parse("2026-09-27T10:00:00Z"));
    }
}
```

`src/test/java/com/plantarena/geo/application/support/InMemoryClusterSnapshotRepository.java`:

```java
package com.plantarena.geo.application.support;

import com.plantarena.geo.domain.ClusterSnapshot;
import com.plantarena.geo.domain.ClusterSnapshotRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory фейк ClusterSnapshotRepository. */
public class InMemoryClusterSnapshotRepository implements ClusterSnapshotRepository {

    public final Map<UUID, ClusterSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public ClusterSnapshot save(ClusterSnapshot snapshot) {
        snapshots.put(snapshot.id(), snapshot);
        return snapshot;
    }

    @Override
    public List<ClusterSnapshot> findByEpochId(UUID epochId) {
        return snapshots.values().stream()
            .filter(snapshot -> snapshot.epochId().equals(epochId)).toList();
    }

    @Override
    public Optional<ClusterSnapshot> findById(UUID id) {
        return Optional.ofNullable(snapshots.get(id));
    }
}
```

`src/test/java/com/plantarena/geo/application/support/InMemoryClusterSnapshotRepositoryContractTest.java`:

```java
package com.plantarena.geo.application.support;

import com.plantarena.geo.ClusterSnapshotRepositoryContractTest;
import com.plantarena.geo.domain.ClusterSnapshotRepository;

/** Фейк честен относительно контракта (раздел 14.2). */
class InMemoryClusterSnapshotRepositoryContractTest
        extends ClusterSnapshotRepositoryContractTest {

    private final InMemoryClusterSnapshotRepository repository =
        new InMemoryClusterSnapshotRepository();

    @Override
    protected ClusterSnapshotRepository repository() {
        return repository;
    }
}
```

- [ ] **Step 3: Запустить — красный**

```bash
./mvnw -q test -Dtest='ClusterAssignmentFacadeTest,InMemoryClusterSnapshotRepositoryContractTest'
```

Ожидание: FAIL (компиляция): нет `ClusterAssignmentFacade`.

- [ ] **Step 4: Реализация фасада + persistence + миграция**

`src/main/java/com/plantarena/geo/application/ClusterAssignmentFacade.java`:

```java
package com.plantarena.geo.application;

import com.plantarena.geo.api.ClusterAssignment;
import com.plantarena.geo.domain.ClusterMember;
import com.plantarena.geo.domain.ClusterSnapshot;
import com.plantarena.geo.domain.ClusterSnapshotRepository;
import com.plantarena.geo.domain.ClusteringPolicy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Реализация опубликованного контракта geo.api.ClusterAssignment:
 * группировка политикой → фиксация снимков (состав и версия неизменны) →
 * ответ с составами участий. Время — из внедрённого Clock.
 */
@Component
public class ClusterAssignmentFacade implements ClusterAssignment {

    private final ClusteringPolicy policy;
    private final ClusterSnapshotRepository snapshots;
    private final Clock clock;

    public ClusterAssignmentFacade(ClusteringPolicy policy,
                                   ClusterSnapshotRepository snapshots, Clock clock) {
        this.policy = policy;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Override
    public List<AssignedCluster> assignClusters(UUID epochId, List<Member> members) {
        List<ClusterMember> domainMembers = members.stream()
            .map(member -> new ClusterMember(member.entryId(), member.userId(),
                member.latitude(), member.longitude(), member.locationVersion()))
            .toList();
        Map<String, List<ClusterMember>> grouped = policy.cluster(domainMembers);
        List<AssignedCluster> result = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<ClusterMember>> entry : grouped.entrySet()) {
            ClusterSnapshot snapshot = ClusterSnapshot.fix(epochId, entry.getKey(),
                policy.policyVersion(), entry.getValue(), clock.instant());
            snapshots.save(snapshot);
            result.add(new AssignedCluster(snapshot.id(), snapshot.clusterKey(),
                entry.getValue().stream().map(ClusterMember::entryId).toList()));
        }
        return result;
    }
}
```

`src/main/resources/db/migration/geo/V2__clustering.sql`:

```sql
-- Контекст geo: снимки кластеров эпох (раздел 8, 11). Состав и версия
-- политики неизменны после фиксации. entry_id — логический UUID (межсхемных
-- FK нет, раздел 11).
CREATE TABLE cluster_snapshot (
    id             UUID         PRIMARY KEY,
    epoch_id       UUID         NOT NULL,
    cluster_key    VARCHAR(12)  NOT NULL,
    policy_version VARCHAR(30)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX cluster_snapshot_epoch_idx ON cluster_snapshot (epoch_id);

CREATE TABLE cluster_member (
    snapshot_id      UUID    NOT NULL REFERENCES cluster_snapshot (id),
    user_id          UUID    NOT NULL,
    entry_id         UUID    NOT NULL,
    location_version BIGINT  NOT NULL,
    PRIMARY KEY (snapshot_id, entry_id)
);
```

`src/main/java/com/plantarena/geo/adapter/out/persistence/ClusterSnapshotJpaEntity.java`:

```java
package com.plantarena.geo.adapter.out.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** JPA-модель снимка кластера (раздел 11); маппинг в домен — явный. */
@Entity
@Table(name = "cluster_snapshot", schema = "geo")
public class ClusterSnapshotJpaEntity {

    @Id
    private UUID id;

    @Column(name = "epoch_id", nullable = false)
    private UUID epochId;

    @Column(name = "cluster_key", nullable = false)
    private String clusterKey;

    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ClusterMemberJpaEntity> members = new ArrayList<>();

    UUID getId() {
        return id;
    }

    UUID getEpochId() {
        return epochId;
    }

    String getClusterKey() {
        return clusterKey;
    }

    String getPolicyVersion() {
        return policyVersion;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    List<ClusterMemberJpaEntity> getMembers() {
        return members;
    }

    void setId(UUID id) {
        this.id = id;
    }

    void setEpochId(UUID epochId) {
        this.epochId = epochId;
    }

    void setClusterKey(String clusterKey) {
        this.clusterKey = clusterKey;
    }

    void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
```

`src/main/java/com/plantarena/geo/adapter/out/persistence/ClusterMemberJpaEntity.java`:

```java
package com.plantarena.geo.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/** JPA-модель участника кластера: PK (snapshot_id, entry_id), раздел 11. */
@Entity
@Table(name = "cluster_member", schema = "geo")
@IdClass(ClusterMemberJpaId.class)
public class ClusterMemberJpaEntity {

    @Id
    @Column(name = "snapshot_id", insertable = false, updatable = false)
    private UUID snapshotId;

    @Id
    @Column(name = "entry_id")
    private UUID entryId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "location_version", nullable = false)
    private long locationVersion;

    @ManyToOne
    @JoinColumn(name = "snapshot_id")
    private ClusterSnapshotJpaEntity snapshot;

    UUID getEntryId() {
        return entryId;
    }

    UUID getUserId() {
        return userId;
    }

    long getLocationVersion() {
        return locationVersion;
    }

    void setEntryId(UUID entryId) {
        this.entryId = entryId;
    }

    void setUserId(UUID userId) {
        this.userId = userId;
    }

    void setLocationVersion(long locationVersion) {
        this.locationVersion = locationVersion;
    }

    void setSnapshot(ClusterSnapshotJpaEntity snapshot) {
        this.snapshot = snapshot;
    }
}
```

`src/main/java/com/plantarena/geo/adapter/out/persistence/ClusterMemberJpaId.java`:

```java
package com.plantarena.geo.adapter.out.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Составной ключ cluster_member (snapshot_id, entry_id). */
public class ClusterMemberJpaId implements Serializable {

    private UUID snapshotId;
    private UUID entryId;

    public ClusterMemberJpaId() {
    }

    public ClusterMemberJpaId(UUID snapshotId, UUID entryId) {
        this.snapshotId = snapshotId;
        this.entryId = entryId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClusterMemberJpaId other)) {
            return false;
        }
        return Objects.equals(snapshotId, other.snapshotId)
            && Objects.equals(entryId, other.entryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(snapshotId, entryId);
    }
}
```

`src/main/java/com/plantarena/geo/adapter/out/persistence/ClusterSnapshotJpaRepository.java`:

```java
package com.plantarena.geo.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data для cluster_snapshot. */
public interface ClusterSnapshotJpaRepository extends JpaRepository<ClusterSnapshotJpaEntity,
        UUID> {

    List<ClusterSnapshotJpaEntity> findByEpochIdOrderByClusterKeyAsc(UUID epochId);
}
```

`src/main/java/com/plantarena/geo/adapter/out/persistence/JpaClusterSnapshotRepository.java`:

```java
package com.plantarena.geo.adapter.out.persistence;

import com.plantarena.geo.domain.ClusterSnapshot;
import com.plantarena.geo.domain.ClusterSnapshotRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA-реализация порта ClusterSnapshotRepository: явный маппинг. */
@Repository
public class JpaClusterSnapshotRepository implements ClusterSnapshotRepository {

    private final ClusterSnapshotJpaRepository jpaRepository;

    public JpaClusterSnapshotRepository(ClusterSnapshotJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public ClusterSnapshot save(ClusterSnapshot snapshot) {
        ClusterSnapshotJpaEntity entity = jpaRepository.findById(snapshot.id())
            .orElseGet(() -> newEntity(snapshot));
        entity.setEpochId(snapshot.epochId());
        entity.setClusterKey(snapshot.clusterKey());
        entity.setPolicyVersion(snapshot.policyVersion());
        entity.setCreatedAt(snapshot.createdAt());
        entity.getMembers().clear();
        for (ClusterSnapshot.SnapshotMember member : snapshot.members()) {
            ClusterMemberJpaEntity memberEntity = new ClusterMemberJpaEntity();
            memberEntity.setEntryId(member.entryId());
            memberEntity.setUserId(member.userId());
            memberEntity.setLocationVersion(member.locationVersion());
            memberEntity.setSnapshot(entity);
            entity.getMembers().add(memberEntity);
        }
        jpaRepository.saveAndFlush(entity);
        return snapshot;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClusterSnapshot> findByEpochId(UUID epochId) {
        return jpaRepository.findByEpochIdOrderByClusterKeyAsc(epochId)
            .stream().map(JpaClusterSnapshotRepository::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ClusterSnapshot> findById(UUID id) {
        return jpaRepository.findById(id).map(JpaClusterSnapshotRepository::toDomain);
    }

    private ClusterSnapshotJpaEntity newEntity(ClusterSnapshot snapshot) {
        ClusterSnapshotJpaEntity entity = new ClusterSnapshotJpaEntity();
        entity.setId(snapshot.id());
        return entity;
    }

    private static ClusterSnapshot toDomain(ClusterSnapshotJpaEntity entity) {
        List<ClusterSnapshot.SnapshotMember> members = entity.getMembers().stream()
            .map(member -> new ClusterSnapshot.SnapshotMember(member.getUserId(),
                member.getEntryId(), member.getLocationVersion()))
            .toList();
        return ClusterSnapshot.restore(entity.getId(), entity.getEpochId(),
            entity.getClusterKey(), entity.getPolicyVersion(), members, entity.getCreatedAt());
    }
}
```

`src/test/java/com/plantarena/geo/adapter/out/persistence/JpaClusterSnapshotRepositoryContractIT.java` (по образцу существующих JPA-контрактных IT — тот же профиль/аннотации, что у `JpaVotingWindowRepositoryContractIT`):

```java
package com.plantarena.geo.adapter.out.persistence;

import com.plantarena.geo.ClusterSnapshotRepositoryContractTest;
import com.plantarena.geo.domain.ClusterSnapshotRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** Контракт JPA-адаптера на Testcontainers PostgreSQL (раздел 14.2). */
class JpaClusterSnapshotRepositoryContractIT extends ClusterSnapshotRepositoryContractTest {

    @Autowired
    private JpaClusterSnapshotRepository repository;

    @Override
    protected ClusterSnapshotRepository repository() {
        return repository;
    }
}
```

(если существующие JPA-контрактные IT используют `@DataJpaTest` + собственную конфигурацию — скопировать их аннотации один в один; контрактный базовый класс уже несёт `@Transactional`.)

- [ ] **Step 5: Запустить — зелёный + полный verify**

```bash
./mvnw -q test -Dtest='ClusterAssignmentFacadeTest,*ClusterSnapshotRepositoryContractTest'
./mvnw -q verify -Dit.test='JpaClusterSnapshotRepositoryContractIT' -DfailIfNoTests=false
```

Ожидание: PASS (миграция V2 применяется, ArchUnit принимает новые пакеты geo — направление geo → никому не нарушено).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/plantarena/geo src/main/java/com/plantarena/config/GeoWiringConfig.java \
  src/main/resources/db/migration/geo/V2__clustering.sql src/test/java/com/plantarena/geo
git commit -m "feat(geo): фасад ClusterAssignment, снимки в PostgreSQL (V2), политика из конфигурации"
```

---

### Task 4: tournaments домен — scope окон, глобальные переходы, `QualificationEpoch`

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/domain/WindowScope.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/EpochStatus.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/QualificationEpoch.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/QualificationEpochRepository.java`
- Modify: `src/main/java/com/plantarena/tournaments/domain/EntryStatus.java`
- Modify: `src/main/java/com/plantarena/tournaments/domain/TournamentType.java`
- Modify: `src/main/java/com/plantarena/tournaments/domain/ParticipantResult.java`
- Modify: `src/main/java/com/plantarena/tournaments/domain/Tournament.java` (фабрика `global` + запрет `finish`)
- Modify: `src/main/java/com/plantarena/tournaments/domain/TournamentEntry.java` (глобальные переходы)
- Modify: `src/main/java/com/plantarena/tournaments/domain/WindowParticipant.java` (`promote()`)
- Modify: `src/main/java/com/plantarena/tournaments/domain/VotingWindow.java` (scope + глобальные фабрики/закрытия)
- Modify: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaVotingWindowRepository.java` (только call-site `restore` — литералы PRIVATE/null до Task 5)
- Test: `src/test/java/com/plantarena/tournaments/domain/QualificationEpochTest.java`
- Test: `src/test/java/com/plantarena/tournaments/domain/TournamentEntryGlobalTest.java`
- Test: `src/test/java/com/plantarena/tournaments/domain/VotingWindowGlobalTest.java`
- Test: `src/test/java/com/plantarena/tournaments/domain/TournamentTest.java` (добавить кейс GLOBAL)

**Interfaces:**
- Consumes: `ParticipantRanking`, `EliminationAlgorithms` (существуют), `VotingWindow.restore` (расширяется).
- Produces (для Tasks 5–7): `WindowScope { PRIVATE, QUALIFICATION, FINAL }`; `EntryStatus + { QUEUED, QUALIFYING, FINAL_PENDING, FINALIST, WITHDRAWN }`; `TournamentType.GLOBAL`; `ParticipantResult.PROMOTED`; `Tournament.global(UUID id, UUID systemCreatorId, Instant now)` + `finish()` кидает для GLOBAL; `TournamentEntry.queueForGlobal(tournamentId, userId, plantId, reservationId, now)` / `withdraw()` / `startQualifying()` / `promoteToFinalPending()` / `becomeFinalist()` / `eliminateFromGlobal()`; `EpochStatus { OPEN, CLOSED }`; `QualificationEpoch.open(id, tournamentId, sequence, opensAt, closesAt, now)` / `close(now)` / `restore(...)`; `VotingWindow.openQualification(tournamentId, epochId, clusterId, clusterKey, sequence, seeds, opensAt, closesAt, now)` / `openFinal(tournamentId, sequence, seeds, opensAt, closesAt, now)` (минимум 1 участник) / `closeQualification(now) → QualificationCloseOutcome(UUID promotedEntryId, List<UUID> eliminatedEntryIds)` / `closeFinal(now) → FinalCloseOutcome(List<UUID> eliminatedEntryIds, List<UUID> survivedEntryIds)` + геттеры `scope()/epochId()/clusterId()/clusterKey()`; новый `restore(...)` со scope/epochId/clusterId/clusterKey.

- [ ] **Step 1: Красные доменные тесты**

`src/test/java/com/plantarena/tournaments/domain/QualificationEpochTest.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Эпоха отбора (раздел 8, алгоритм 2): интервал и переход OPEN → CLOSED. */
@DisplayName("QualificationEpoch: открытие и закрытие эпохи")
class QualificationEpochTest {

    private static final Instant OPENS = Instant.parse("2026-09-27T10:00:00Z");
    private static final Instant CLOSES = OPENS.plusSeconds(3600);

    @Test
    @DisplayName("эпоха открывается со статусом OPEN и полуоткрытым интервалом")
    void открытие() {
        QualificationEpoch epoch = QualificationEpoch.open(UUID.randomUUID(),
            UUID.randomUUID(), 1, OPENS, CLOSES, OPENS);
        assertThat(epoch.status()).isEqualTo(EpochStatus.OPEN);
        assertThat(epoch.sequence()).isEqualTo(1);
    }

    @Test
    @DisplayName("закрытие возможно только после closesAt; повтор — ошибка состояния")
    void закрытие() {
        QualificationEpoch epoch = epoch();
        assertThatThrownBy(() -> epoch.close(CLOSES.minusNanos(1)))
            .isInstanceOf(IllegalStateException.class);
        epoch.close(CLOSES);
        assertThat(epoch.status()).isEqualTo(EpochStatus.CLOSED);
        assertThatThrownBy(() -> epoch.close(CLOSES.plusSeconds(1)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("opensAt < closesAt и sequence >= 1")
    void инварианты() {
        assertThatThrownBy(() -> QualificationEpoch.open(UUID.randomUUID(),
            UUID.randomUUID(), 0, OPENS, CLOSES, OPENS))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QualificationEpoch.open(UUID.randomUUID(),
            UUID.randomUUID(), 1, CLOSES, OPENS, OPENS))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private QualificationEpoch epoch() {
        return QualificationEpoch.open(UUID.randomUUID(), UUID.randomUUID(), 1,
            OPENS, CLOSES, OPENS);
    }
}
```

`src/test/java/com/plantarena/tournaments/domain/TournamentEntryGlobalTest.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Глобальные переходы участия (раздел 11): QUEUED → … → ELIMINATED, WITHDRAWN. */
@DisplayName("TournamentEntry: глобальный жизненный цикл")
class TournamentEntryGlobalTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Test
    @DisplayName("полный путь: QUEUED → QUALIFYING → FINAL_PENDING → FINALIST → ELIMINATED")
    void путь_финалиста_до_поражения() {
        TournamentEntry entry = TournamentEntry.queueForGlobal(UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW);
        assertThat(entry.status()).isEqualTo(EntryStatus.QUEUED);
        entry.startQualifying();
        assertThat(entry.status()).isEqualTo(EntryStatus.QUALIFYING);
        entry.promoteToFinalPending();
        assertThat(entry.status()).isEqualTo(EntryStatus.FINAL_PENDING);
        entry.becomeFinalist();
        assertThat(entry.status()).isEqualTo(EntryStatus.FINALIST);
        entry.eliminateFromGlobal();
        assertThat(entry.status()).isEqualTo(EntryStatus.ELIMINATED);
    }

    @Test
    @DisplayName("QUALIFYING → ELIMINATED допустим (поражение в квалификации)")
    void поражение_в_квалификации() {
        TournamentEntry entry = queued();
        entry.startQualifying();
        entry.eliminateFromGlobal();
        assertThat(entry.status()).isEqualTo(EntryStatus.ELIMINATED);
    }

    @Test
    @DisplayName("WITHDRAWN только из QUEUED; снятое не участвует")
    void снятие_только_из_очереди() {
        TournamentEntry entry = queued();
        entry.withdraw();
        assertThat(entry.status()).isEqualTo(EntryStatus.WITHDRAWN);
        assertThatThrownBy(entry::startQualifying)
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(entry::withdraw)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("недопустимые переходы — ошибка состояния (QUEUED нельзя в финал)")
    void недопустимые_переходы() {
        TournamentEntry queued = queued();
        assertThatThrownBy(queued::promoteToFinalPending)
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(queued::becomeFinalist)
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(queued::eliminateFromGlobal)
            .isInstanceOf(IllegalStateException.class);
        TournamentEntry finalist = finalist();
        assertThatThrownBy(finalist::startQualifying)
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(finalist::promoteToFinalPending)
            .isInstanceOf(IllegalStateException.class);
    }

    private TournamentEntry queued() {
        return TournamentEntry.queueForGlobal(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), NOW);
    }

    private TournamentEntry finalist() {
        TournamentEntry entry = queued();
        entry.startQualifying();
        entry.promoteToFinalPending();
        entry.becomeFinalist();
        return entry;
    }
}
```

`src/test/java/com/plantarena/tournaments/domain/VotingWindowGlobalTest.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Глобальные окна (раздел 8, алгоритмы 3–8): квалификация и финал. */
@DisplayName("VotingWindow: scope QUALIFICATION/FINAL")
class VotingWindowGlobalTest {

    private static final UUID TOURNAMENT = UUID.randomUUID();
    private static final UUID EPOCH = UUID.randomUUID();
    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID USER_1 = UUID.randomUUID();
    private static final UUID USER_2 = UUID.randomUUID();
    private static final UUID USER_3 = UUID.randomUUID();
    private static final UUID ENTRY_1 = UUID.randomUUID();
    private static final UUID ENTRY_2 = UUID.randomUUID();
    private static final UUID ENTRY_3 = UUID.randomUUID();
    private static final Instant OPENS = Instant.parse("2026-09-27T10:00:00Z");
    private static final Instant CLOSES = OPENS.plusSeconds(60);

    @Test
    @DisplayName("квалификационное окно допускает единственного участника (алгоритм 4)")
    void квалификация_одного() {
        VotingWindow window = VotingWindow.openQualification(TOURNAMENT, EPOCH, CLUSTER,
            "u4pu", 1, List.of(seed(ENTRY_1, USER_1)), OPENS, CLOSES, OPENS);
        assertThat(window.scope()).isEqualTo(WindowScope.QUALIFICATION);
        assertThat(window.epochId()).isEqualTo(EPOCH);
        assertThat(window.clusterId()).isEqualTo(CLUSTER);
        assertThat(window.clusterKey()).isEqualTo("u4pu");
        VotingWindow.QualificationCloseOutcome outcome = window.closeQualification(CLOSES);
        assertThat(outcome.promotedEntryId()).isEqualTo(ENTRY_1);
        assertThat(outcome.eliminatedEntryIds()).isEmpty();
        assertThat(window.participants().iterator().next().result())
            .isEqualTo(ParticipantResult.PROMOTED);
    }

    @Test
    @DisplayName("закрытие квалификации: top-1 PROMOTED, остальные ELIMINATED (алгоритм 3)")
    void квалификация_top1() {
        VotingWindow window = qualification3();
        window.castVote(VotingSubject.user(USER_3), ENTRY_1, VoteValue.LIKE, OPENS);
        VotingWindow.QualificationCloseOutcome outcome = window.closeQualification(CLOSES);
        assertThat(outcome.promotedEntryId()).isEqualTo(ENTRY_1);
        assertThat(outcome.eliminatedEntryIds()).containsExactly(ENTRY_2, ENTRY_3);
    }

    @Test
    @DisplayName("закрытие финала: n=3 → выбывает max(1, floor(3/2)) = 1 худший (алгоритм 7)")
    void финал_выбывание() {
        VotingWindow window = final3();
        window.castVote(VotingSubject.user(USER_3), ENTRY_1, VoteValue.LIKE, OPENS);
        VotingWindow.FinalCloseOutcome outcome = window.closeFinal(CLOSES);
        assertThat(outcome.eliminatedEntryIds()).containsExactly(ENTRY_2);
        assertThat(outcome.survivedEntryIds()).containsExactly(ENTRY_1, ENTRY_3);
    }

    @Test
    @DisplayName("финал n=2 → выбывает 1; n=1 → лидер остаётся SURVIVED без выбывания")
    void финал_крайние_случаи() {
        VotingWindow two = VotingWindow.openFinal(TOURNAMENT, 1,
            List.of(seed(ENTRY_1, USER_1), seed(ENTRY_2, USER_2)), OPENS, CLOSES, OPENS);
        assertThat(two.closeFinal(CLOSES).eliminatedEntryIds()).hasSize(1);
        assertThat(two.closeFinal(CLOSES).survivedEntryIds()).hasSize(1);

        VotingWindow single = VotingWindow.openFinal(TOURNAMENT, 1,
            List.of(seed(ENTRY_1, USER_1)), OPENS, CLOSES, OPENS);
        VotingWindow.FinalCloseOutcome outcome = single.closeFinal(CLOSES);
        assertThat(outcome.eliminatedEntryIds()).isEmpty();
        assertThat(outcome.survivedEntryIds()).isEmpty(); // лидер: результат SURVIVED
        assertThat(single.participants().iterator().next().result())
            .isEqualTo(ParticipantResult.SURVIVED);
    }

    @Test
    @DisplayName("закрытие не по scope и до дедлайна — ошибка состояния; повтор — ошибка")
    void ошибки_состояния() {
        VotingWindow qualification = qualification3();
        assertThatThrownBy(() -> qualification.closeFinal(CLOSES))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> qualification.closeQualification(CLOSES.minusNanos(1)))
            .isInstanceOf(IllegalStateException.class);
        qualification.closeQualification(CLOSES);
        assertThatThrownBy(() -> qualification.closeQualification(CLOSES.plusSeconds(1)))
            .isInstanceOf(IllegalStateException.class);
    }

    private VotingWindow qualification3() {
        return VotingWindow.openQualification(TOURNAMENT, EPOCH, CLUSTER, "u4pu", 1,
            List.of(seed(ENTRY_1, USER_1), seed(ENTRY_2, USER_2), seed(ENTRY_3, USER_3)),
            OPENS, CLOSES, OPENS);
    }

    private VotingWindow final3() {
        return VotingWindow.openFinal(TOURNAMENT, 1,
            List.of(seed(ENTRY_1, USER_1), seed(ENTRY_2, USER_2), seed(ENTRY_3, USER_3)),
            OPENS, CLOSES, OPENS);
    }

    private VotingWindow.ParticipantSeed seed(UUID entryId, UUID userId) {
        return new VotingWindow.ParticipantSeed(entryId, userId,
            Instant.parse("2026-09-27T09:00:00Z"));
    }
}
```

Добавить в `src/test/java/com/plantarena/tournaments/domain/TournamentTest.java`:

```java
    @Test
    @DisplayName("глобальный турнир никогда не завершается (раздел 8)")
    void глобальный_не_завершается() {
        UUID id = UUID.randomUUID();
        Tournament global = Tournament.global(id, id,
            Instant.parse("2026-09-27T10:00:00Z"));
        assertThat(global.type()).isEqualTo(TournamentType.GLOBAL);
        assertThat(global.status()).isEqualTo(TournamentStatus.RUNNING);
        assertThatThrownBy(() -> global.finish(Instant.parse("2026-09-27T11:00:00Z")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("никогда не завершается");
    }
```

- [ ] **Step 2: Запустить — красный**

```bash
./mvnw -q test -Dtest='QualificationEpochTest,TournamentEntryGlobalTest,VotingWindowGlobalTest,TournamentTest'
```

Ожидание: FAIL (компиляция): нет `WindowScope`, `QualificationEpoch` и т. д.

- [ ] **Step 3: Реализация домена**

`src/main/java/com/plantarena/tournaments/domain/WindowScope.java`:

```java
package com.plantarena.tournaments.domain;

/** Scope окна (раздел 11): раунд private-турнира, квалификация или финал. */
public enum WindowScope {
    PRIVATE, QUALIFICATION, FINAL
}
```

`src/main/java/com/plantarena/tournaments/domain/EpochStatus.java`:

```java
package com.plantarena.tournaments.domain;

/** Статус эпохи отбора (раздел 8): открыта/закрыта. */
public enum EpochStatus {
    OPEN, CLOSED
}
```

`src/main/java/com/plantarena/tournaments/domain/EntryStatus.java` — заменить целиком:

```java
package com.plantarena.tournaments.domain;

/**
 * Статус участия (раздел 11). PRIVATE: ACTIVE → ELIMINATED/WINNER.
 * GLOBAL: QUEUED → QUALIFYING → FINAL_PENDING → FINALIST → ELIMINATED,
 * плюс WITHDRAWN только из QUEUED; QUALIFYING → ELIMINATED допустим.
 */
public enum EntryStatus {
    ACTIVE, ELIMINATED, WINNER,
    QUEUED, QUALIFYING, FINAL_PENDING, FINALIST, WITHDRAWN
}
```

`src/main/java/com/plantarena/tournaments/domain/TournamentType.java` — заменить целиком:

```java
package com.plantarena.tournaments.domain;

/** Тип турнира: PRIVATE — закрытый, GLOBAL — единственный глобальный (раздел 8). */
public enum TournamentType {
    PRIVATE, GLOBAL
}
```

`src/main/java/com/plantarena/tournaments/domain/ParticipantResult.java` — добавить `PROMOTED` в enum (итог top-1 квалификационного окна, раздел 11).

`src/main/java/com/plantarena/tournaments/domain/Tournament.java` — два изменения:

1) фабрика глобального турнира (рядом с `createDraft`):

```java
    /**
     * Единственный глобальный турнир (раздел 8): фиксированный id, статус
     * RUNNING навсегда; параметры private-режима — заглушки, тайминги — в
     * конфигурации (дизайн итерации 7, решение 1). creator — системный UUID.
     */
    public static Tournament global(UUID id, UUID systemCreatorId, Instant now) {
        return new Tournament(id, systemCreatorId, "Глобальный турнир", null,
            TournamentType.GLOBAL, TournamentStatus.RUNNING,
            EliminationAlgorithmKind.ROUND_ELIMINATION, now.plus(Duration.ofHours(1)),
            Duration.ofHours(1), 0.5, 2, null, Set.of(), now, 0);
    }
```

2) запрет завершения (первой строкой `finish`):

```java
    /** Завершение: победитель определён закрытием окна (раздел 7). */
    public void finish(Instant now) {
        if (type == TournamentType.GLOBAL) {
            throw new IllegalStateException("Глобальный турнир никогда не завершается (раздел 8)");
        }
        requireStatus(TournamentStatus.RUNNING);
        status = TournamentStatus.FINISHED;
    }
```

`src/main/java/com/plantarena/tournaments/domain/TournamentEntry.java` — добавить глобальные операции (после `declareWinner`):

```java
    /** Глобальная заявка: одобренное растение в очередь следующей эпохи (раздел 8). */
    public static TournamentEntry queueForGlobal(UUID tournamentId, UUID userId, UUID plantId,
                                                 UUID reservationId, Instant now) {
        return new TournamentEntry(UUID.randomUUID(), tournamentId, userId, plantId,
            reservationId, EntryStatus.QUEUED, now);
    }

    /** Снятие заявки из очереди (раздел 13): только QUEUED. */
    public void withdraw() {
        requireStatus(EntryStatus.QUEUED);
        status = EntryStatus.WITHDRAWN;
    }

    /** Включение в эпоху отбора (раздел 8, алгоритм 2): QUEUED → QUALIFYING. */
    public void startQualifying() {
        requireStatus(EntryStatus.QUEUED);
        status = EntryStatus.QUALIFYING;
    }

    /** Победа в квалификации (алгоритм 5): QUALIFYING → FINAL_PENDING. */
    public void promoteToFinalPending() {
        requireStatus(EntryStatus.QUALIFYING);
        status = EntryStatus.FINAL_PENDING;
    }

    /** Включение в финальное окно (алгоритм 6): FINAL_PENDING → FINALIST. */
    public void becomeFinalist() {
        requireStatus(EntryStatus.FINAL_PENDING);
        status = EntryStatus.FINALIST;
    }

    /** Глобальное поражение (алгоритмы 3, 8): QUALIFYING/FINALIST → ELIMINATED. */
    public void eliminateFromGlobal() {
        if (status != EntryStatus.QUALIFYING && status != EntryStatus.FINALIST) {
            throw new IllegalStateException("Итог участия уже зафиксирован: " + status);
        }
        status = EntryStatus.ELIMINATED;
    }

    private void requireStatus(EntryStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                "Ожидается статус " + expected + ", текущий: " + status);
        }
    }
```

`src/main/java/com/plantarena/tournaments/domain/WindowParticipant.java` — добавить (рядом с `declareWinner`):

```java
    void promote() {
        requireActive();
        result = ParticipantResult.PROMOTED;
    }
```

`src/main/java/com/plantarena/tournaments/domain/QualificationEpoch.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат tournaments (раздел 8, алгоритм 2): эпоха глобального отбора с
 * фиксированным интервалом [opensAt, closesAt). Состав эпохи — её окна
 * (кластеры geo + участия QUALIFYING); сам агрегат хранит только границы и
 * статус. Открывается только при наличии QUEUED-заявок (use case).
 */
public final class QualificationEpoch {

    private final UUID id;
    private final UUID tournamentId;
    private final int sequence;
    private EpochStatus status;
    private final Instant opensAt;
    private final Instant closesAt;
    private final Instant createdAt;
    private long version;

    private QualificationEpoch(UUID id, UUID tournamentId, int sequence, EpochStatus status,
                               Instant opensAt, Instant closesAt, Instant createdAt,
                               long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tournamentId = Objects.requireNonNull(tournamentId, "tournamentId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence >= 1");
        }
        this.sequence = sequence;
        this.status = Objects.requireNonNull(status, "status");
        this.opensAt = Objects.requireNonNull(opensAt, "opensAt");
        this.closesAt = Objects.requireNonNull(closesAt, "closesAt");
        if (!opensAt.isBefore(closesAt)) {
            throw new IllegalArgumentException("opensAt < closesAt (полуоткрытый интервал)");
        }
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
    }

    /** Открытие эпохи: id задаётся заранее (geo-снимки ссылаются на него). */
    public static QualificationEpoch open(UUID id, UUID tournamentId, int sequence,
                                          Instant opensAt, Instant closesAt, Instant now) {
        return new QualificationEpoch(id, tournamentId, sequence, EpochStatus.OPEN,
            opensAt, closesAt, now, 0L);
    }

    /** Восстановление из хранилища (использует только persistence-адаптер). */
    public static QualificationEpoch restore(UUID id, UUID tournamentId, int sequence,
                                             EpochStatus status, Instant opensAt,
                                             Instant closesAt, Instant createdAt,
                                             long version) {
        return new QualificationEpoch(id, tournamentId, sequence, status, opensAt,
            closesAt, createdAt, version);
    }

    /** Закрытие эпохи: все её окна закрыты и дедлайн наступил. */
    public void close(Instant now) {
        if (status != EpochStatus.OPEN) {
            throw new IllegalStateException("Эпоха уже закрыта: " + id);
        }
        if (now.isBefore(closesAt)) {
            throw new IllegalStateException("Эпоха открыта до " + closesAt);
        }
        status = EpochStatus.CLOSED;
    }

    public UUID id() {
        return id;
    }

    public UUID tournamentId() {
        return tournamentId;
    }

    public int sequence() {
        return sequence;
    }

    public EpochStatus status() {
        return status;
    }

    public Instant opensAt() {
        return opensAt;
    }

    public Instant closesAt() {
        return closesAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }
}
```

`src/main/java/com/plantarena/tournaments/domain/QualificationEpochRepository.java`:

```java
package com.plantarena.tournaments.domain;

import java.util.Optional;
import java.util.UUID;

/** Порт репозитория агрегата QualificationEpoch (раздел 8). */
public interface QualificationEpochRepository {

    QualificationEpoch save(QualificationEpoch epoch);

    Optional<QualificationEpoch> findById(UUID id);

    /** Открытая эпоха турнира (не более одной — частичный уникальный индекс). */
    Optional<QualificationEpoch> findOpenByTournamentId(UUID tournamentId);

    /** Последняя эпоха турнира (максимум sequence) — нумерация следующих. */
    Optional<QualificationEpoch> findLastByTournamentId(UUID tournamentId);
}
```

`src/main/java/com/plantarena/tournaments/domain/VotingWindow.java` — изменения:

1) поля (после `closesAt`):

```java
    private final WindowScope scope;
    private final UUID epochId;
    private final UUID clusterId;
    private final String clusterKey;
```

2) приватный конструктор — расширить сигнатуру (в конце, перед проверкой интервала):

```java
    private VotingWindow(UUID id, UUID tournamentId, int sequence, WindowScope scope,
                         UUID epochId, UUID clusterId, String clusterKey, WindowStatus status,
                         Instant opensAt, Instant closesAt, Instant createdAt, long version) {
        // ... существующие проверки ...
        this.scope = Objects.requireNonNull(scope, "scope");
        this.epochId = epochId;
        this.clusterId = clusterId;
        this.clusterKey = clusterKey;
        if ((scope == WindowScope.QUALIFICATION) != (epochId != null)) {
            throw new IllegalArgumentException("epochId обязателен только для квалификации");
        }
        if (scope != WindowScope.QUALIFICATION && (clusterId != null || clusterKey != null)) {
            throw new IllegalArgumentException("Кластер — только у квалификационного окна");
        }
    }
```

3) `open(...)` — существующая фабрика передаёт `WindowScope.PRIVATE, null, null, null` в конструктор (тело не меняется, кроме вызова конструктора).

4) новые фабрики (после `open`):

```java
    /**
     * Квалификационное окно кластера эпохи (раздел 8, алгоритм 2): минимум
     * один участник (алгоритм 4 — единственный проходит без голосов).
     * sequence = номер эпохи; уникальность (epochId, clusterId) — БД.
     */
    public static VotingWindow openQualification(UUID tournamentId, UUID epochId,
                                                 UUID clusterId, String clusterKey,
                                                 int sequence, List<ParticipantSeed> seeds,
                                                 Instant opensAt, Instant closesAt,
                                                 Instant now) {
        return openScoped(WindowScope.QUALIFICATION, tournamentId, sequence, seeds,
            epochId, clusterId, clusterKey, opensAt, closesAt, now);
    }

    /**
     * Финальное окно (раздел 8, алгоритм 6): выжившие предыдущего + новые
     * финалисты; минимум один участник (n=1 — лидер, алгоритм 7).
     */
    public static VotingWindow openFinal(UUID tournamentId, int sequence,
                                         List<ParticipantSeed> seeds, Instant opensAt,
                                         Instant closesAt, Instant now) {
        return openScoped(WindowScope.FINAL, tournamentId, sequence, seeds,
            null, null, null, opensAt, closesAt, now);
    }

    private static VotingWindow openScoped(WindowScope scope, UUID tournamentId, int sequence,
                                           List<ParticipantSeed> seeds, UUID epochId,
                                           UUID clusterId, String clusterKey, Instant opensAt,
                                           Instant closesAt, Instant now) {
        Objects.requireNonNull(seeds, "seeds");
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException(
                "Глобальное окно открывается минимум с одним участником");
        }
        VotingWindow window = new VotingWindow(UUID.randomUUID(), tournamentId, sequence,
            scope, epochId, clusterId, clusterKey, WindowStatus.OPEN, opensAt, closesAt,
            now, 0L);
        for (ParticipantSeed seed : seeds) {
            if (window.participantsByEntry.containsKey(seed.entryId())) {
                throw new IllegalArgumentException("Дубликат участника в окне: " + seed.entryId());
            }
            window.participantsByEntry.put(seed.entryId(),
                WindowParticipant.newParticipant(seed.entryId(), seed.userId(), seed.joinedAt()));
        }
        return window;
    }
```

5) `restore(...)` — новая сигнатура (старая удаляется):

```java
    /** Восстановление из хранилища (использует только persistence-адаптер). */
    public static VotingWindow restore(UUID id, UUID tournamentId, int sequence,
                                       WindowScope scope, UUID epochId, UUID clusterId,
                                       String clusterKey, WindowStatus status, Instant opensAt,
                                       Instant closesAt, Instant createdAt, long version,
                                       Collection<WindowParticipant> participants,
                                       Collection<Vote> votes) {
        VotingWindow window = new VotingWindow(id, tournamentId, sequence, scope, epochId,
            clusterId, clusterKey, status, opensAt, closesAt, createdAt, version);
        participants.forEach(participant ->
            window.participantsByEntry.put(participant.entryId(), participant));
        votes.forEach(vote ->
            window.votesByKey.put(voteKey(vote.subjectKey(), vote.entryId()), vote));
        return window;
    }
```

6) глобальные закрытия (после `close`):

```java
    /**
     * Закрытие квалификационного окна (раздел 8, алгоритмы 3–5): top-1
     * рейтинга — PROMOTED (в финал), остальные — ELIMINATED. Рейтинг:
     * score DESC, joinedAt ASC, entryId ASC (допущение 8).
     */
    public QualificationCloseOutcome closeQualification(Instant now) {
        requireScope(WindowScope.QUALIFICATION);
        requireClosable(now);
        status = WindowStatus.CLOSED;
        List<WindowParticipant> ranked = ParticipantRanking.rank(participantsByEntry.values());
        WindowParticipant top = ranked.get(0);
        top.promote();
        List<UUID> eliminated = new ArrayList<>(ranked.size() - 1);
        for (int i = 1; i < ranked.size(); i++) {
            WindowParticipant worst = ranked.get(i);
            worst.eliminate();
            eliminated.add(worst.entryId());
        }
        return new QualificationCloseOutcome(top.entryId(), List.copyOf(eliminated));
    }

    /**
     * Закрытие финального окна (раздел 8, алгоритмы 7–8): n ≥ 2 — выбывает
     * max(1, floor(n/2)) худших, выжившие SURVIVED; n = 1 — лидер остаётся
     * (SURVIVED без выбывания). Повторное закрытие — ошибка состояния.
     */
    public FinalCloseOutcome closeFinal(Instant now) {
        requireScope(WindowScope.FINAL);
        requireClosable(now);
        status = WindowStatus.CLOSED;
        List<WindowParticipant> ranked = ParticipantRanking.rank(participantsByEntry.values());
        if (ranked.size() == 1) {
            ranked.get(0).survive();
            return new FinalCloseOutcome(List.of(), List.of());
        }
        int eliminatedCount = Math.max(1, ranked.size() / 2);
        List<UUID> eliminated = new ArrayList<>(eliminatedCount);
        for (int i = 0; i < eliminatedCount; i++) {
            WindowParticipant worst = ranked.get(ranked.size() - 1 - i);
            worst.eliminate();
            eliminated.add(worst.entryId());
        }
        List<UUID> survived = new ArrayList<>(ranked.size() - eliminatedCount);
        for (int i = 0; i < ranked.size() - eliminatedCount; i++) {
            ranked.get(i).survive();
            survived.add(ranked.get(i).entryId());
        }
        return new FinalCloseOutcome(List.copyOf(eliminated), List.copyOf(survived));
    }

    private void requireScope(WindowScope expected) {
        if (scope != expected) {
            throw new IllegalStateException("Ожидается scope " + expected + ", текущий: " + scope);
        }
    }

    private void requireClosable(Instant now) {
        if (status != WindowStatus.OPEN) {
            throw new IllegalStateException("Окно уже закрыто: " + id);
        }
        if (now.isBefore(closesAt)) {
            throw new IllegalStateException("Окно открыто до " + closesAt);
        }
    }
```

7) новые записи-итоги (рядом с `CloseOutcome`) и геттеры:

```java
    /** Итог квалификации: продвинутый в финал и выбывшие (алгоритмы 3–5). */
    public record QualificationCloseOutcome(UUID promotedEntryId, List<UUID> eliminatedEntryIds) {
    }

    /** Итог финала: выбывшие и выжившие (n=1 — оба списка пусты, лидер жив). */
    public record FinalCloseOutcome(List<UUID> eliminatedEntryIds, List<UUID> survivedEntryIds) {
    }
```

```java
    public WindowScope scope() {
        return scope;
    }

    public UUID epochId() {
        return epochId;
    }

    public UUID clusterId() {
        return clusterId;
    }

    public String clusterKey() {
        return clusterKey;
    }
```

8) `JpaVotingWindowRepository.toDomain(...)` (Task 4 — временные литералы до Task 5): заменить вызов `VotingWindow.restore(...)` на:

```java
        return VotingWindow.restore(entity.getId(), entity.getTournamentId(),
            entity.getSequence(), WindowScope.PRIVATE, null, null, null,
            WindowStatus.valueOf(entity.getStatus()),
            entity.getOpensAt(), entity.getClosesAt(), entity.getCreatedAt(),
            entity.getVersion(), participants, votes);
```

(в Task 5 литералы заменяются реальными колонками; до V4 в БД только PRIVATE-окна — поведение не меняется).

- [ ] **Step 4: Запустить — зелёный + полный verify**

```bash
./mvnw -q test -Dtest='QualificationEpochTest,TournamentEntryGlobalTest,VotingWindowGlobalTest,TournamentTest'
./mvnw -q verify
```

Ожидание: PASS (существующие тесты окна/закрытия не меняются — `close()` для PRIVATE остался прежним).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/plantarena/tournaments/domain \
  src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaVotingWindowRepository.java \
  src/test/java/com/plantarena/tournaments/domain
git commit -m "feat(tournaments): домен глобального турнира — scope окон, эпохи, переходы участий"
```

---

### Task 5: persistence — миграция V4, порты, JPA, контрактные тесты, скрытие GLOBAL

**Files:**
- Create: `src/main/resources/db/migration/tournaments/V4__global.sql`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/QualificationEpochJpaEntity.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/QualificationEpochJpaRepository.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaQualificationEpochRepository.java`
- Modify: `src/main/java/com/plantarena/tournaments/domain/VotingWindowRepository.java`
- Modify: `src/main/java/com/plantarena/tournaments/domain/TournamentEntryRepository.java`
- Modify: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/VotingWindowJpaEntity.java`
- Modify: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/VotingWindowJpaRepository.java`
- Modify: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaVotingWindowRepository.java`
- Modify: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/TournamentEntryJpaRepository.java`
- Modify: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaTournamentEntryRepository.java`
- Modify: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/TournamentJpaRepository.java`
- Modify: `src/main/java/com/plantarena/tournaments/application/CloseVotingWindowService.java`
- Modify: `src/main/java/com/plantarena/tournaments/application/TournamentQueryService.java`
- Test: `src/test/java/com/plantarena/tournaments/VotingWindowRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/tournaments/TournamentEntryRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/tournaments/application/support/InMemoryVotingWindowRepository.java`
- Test: `src/test/java/com/plantarena/tournaments/application/support/InMemoryTournamentEntryRepository.java`
- Test: `src/test/java/com/plantarena/tournaments/application/CloseVotingWindowServiceTest.java`
- Test: `src/test/java/com/plantarena/tournaments/application/TournamentQueryServiceTest.java`
- Test: `src/test/java/com/plantarena/tournaments/adapter/out/persistence/JpaVotingWindowRepositoryContractIT.java`
- Test: `src/test/java/com/plantarena/tournaments/adapter/out/persistence/JpaTournamentEntryRepositoryContractIT.java`

**Interfaces:**
- Consumes: домен Task 4; `GlobalCompetitionId` ещё не существует — в миграции фиксированный UUID пишется литералом `00000007-10ba-4000-8000-000000000001` (константа появится в Task 6 с тем же значением).
- Produces (для Task 6): порты: `VotingWindowRepository + { List<UUID> findDueForCloseByScope(WindowScope, Instant, int); Optional<VotingWindow> findOpenByScope(UUID tournamentId, WindowScope); Optional<VotingWindow> findLatestByTournamentIdAndScope(UUID, WindowScope); List<VotingWindow> findOpenByEpochId(UUID); Optional<VotingWindow> findOpenByClusterId(UUID); long countOpenByEpochId(UUID) }`; `TournamentEntryRepository + { Optional<TournamentEntry> findActiveGlobalByUserId(UUID tournamentId, UUID userId); List<TournamentEntry> findByTournamentIdAndStatus(UUID, EntryStatus) }`; `QualificationEpochRepository` (Task 4) с JPA-реализацией.

- [ ] **Step 1: Миграция V4**

`src/main/resources/db/migration/tournaments/V4__global.sql`:

```sql
-- Контекст tournaments: глобальный турнир (раздел 8, 11): scope окон,
-- эпохи отбора, глобальные статусы участий. Enum → VARCHAR + CHECK.
-- Глобальный турнир — единственная запись типа GLOBAL с фиксированным id
-- (создаётся bootstrap-раннером, не миграцией).

ALTER TABLE tournament DROP CONSTRAINT tournament_type_check;
ALTER TABLE tournament ADD CONSTRAINT tournament_type_check
    CHECK (type IN ('PRIVATE', 'GLOBAL'));

-- участия: глобальный жизненный цикл (раздел 11); FINAL_PENDING длиннее 10
ALTER TABLE tournament_entry ALTER COLUMN status TYPE VARCHAR(15);
ALTER TABLE tournament_entry DROP CONSTRAINT tournament_entry_status_check;
ALTER TABLE tournament_entry ADD CONSTRAINT tournament_entry_status_check
    CHECK (status IN ('ACTIVE', 'ELIMINATED', 'WINNER', 'QUEUED', 'QUALIFYING',
                      'FINAL_PENDING', 'FINALIST', 'WITHDRAWN'));
-- уникальность private-пары остаётся частичной по private-статусам:
-- история глобальных участий одного пользователя не конфликтует
ALTER TABLE tournament_entry DROP CONSTRAINT tournament_entry_pair_uidx;
CREATE UNIQUE INDEX tournament_entry_pair_uidx ON tournament_entry (tournament_id, user_id)
    WHERE status IN ('ACTIVE', 'ELIMINATED', 'WINNER');
-- одно активное глобальное участие на пользователя (допущение 5)
CREATE UNIQUE INDEX tournament_entry_one_active_global_uidx ON tournament_entry (user_id)
    WHERE tournament_id = '00000007-10ba-4000-8000-000000000001'::uuid
      AND status IN ('QUEUED', 'QUALIFYING', 'FINAL_PENDING', 'FINALIST');
CREATE INDEX tournament_entry_global_status_idx ON tournament_entry (tournament_id, status);

-- окна: scope + связь с эпохой/кластером (раздел 11)
ALTER TABLE voting_window ADD COLUMN scope VARCHAR(13) NOT NULL DEFAULT 'PRIVATE'
    CHECK (scope IN ('PRIVATE', 'QUALIFICATION', 'FINAL'));
ALTER TABLE voting_window ADD COLUMN epoch_id UUID;
ALTER TABLE voting_window ADD COLUMN cluster_id UUID;
ALTER TABLE voting_window ADD COLUMN cluster_key VARCHAR(12);
ALTER TABLE voting_window DROP CONSTRAINT voting_window_sequence_uidx;
-- PRIVATE/FINAL: последовательность уникальна в своём scope
CREATE UNIQUE INDEX voting_window_private_seq_uidx ON voting_window (tournament_id, sequence)
    WHERE scope = 'PRIVATE';
CREATE UNIQUE INDEX voting_window_final_seq_uidx ON voting_window (tournament_id, sequence)
    WHERE scope = 'FINAL';
-- QUALIFICATION: одно окно на кластер эпохи
CREATE UNIQUE INDEX voting_window_qualification_cell_uidx ON voting_window (epoch_id, cluster_id)
    WHERE scope = 'QUALIFICATION';
DROP INDEX voting_window_one_open_uidx;
-- «одно открытое окно» — только для PRIVATE и FINAL (квалификационных окон
-- открытой эпохи много — по одному на кластер)
CREATE UNIQUE INDEX voting_window_one_open_private_uidx ON voting_window (tournament_id)
    WHERE status = 'OPEN' AND scope = 'PRIVATE';
CREATE UNIQUE INDEX voting_window_one_open_final_uidx ON voting_window (tournament_id)
    WHERE status = 'OPEN' AND scope = 'FINAL';
CREATE INDEX voting_window_scope_due_idx ON voting_window (scope, status, closes_at);

-- итог участника окна: PROMOTED — top-1 квалификации (раздел 11)
ALTER TABLE window_participant DROP CONSTRAINT window_participant_result_check;
ALTER TABLE window_participant ADD CONSTRAINT window_participant_result_check
    CHECK (result IN ('ACTIVE', 'SURVIVED', 'ELIMINATED', 'WINNER', 'PROMOTED'));

-- эпохи отбора (раздел 8, 11): явная запись для восстановления границ
-- и состава после рестарта
CREATE TABLE qualification_epoch (
    id            UUID PRIMARY KEY,
    tournament_id UUID        NOT NULL REFERENCES tournament (id),
    sequence      INTEGER     NOT NULL CHECK (sequence >= 1),
    status        VARCHAR(10) NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    opens_at      TIMESTAMPTZ NOT NULL,
    closes_at     TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT qualification_epoch_interval_chk CHECK (opens_at < closes_at)
);
CREATE UNIQUE INDEX qualification_epoch_seq_uidx ON qualification_epoch (tournament_id, sequence);
CREATE UNIQUE INDEX qualification_epoch_one_open_uidx ON qualification_epoch (tournament_id)
    WHERE status = 'OPEN';
```

- [ ] **Step 2: Расширение портов**

`VotingWindowRepository` — добавить методы:

```java
    /** Просроченные OPEN-окна конкретного scope (глобальные границы, раздел 8). */
    List<UUID> findDueForCloseByScope(WindowScope scope, Instant now, int limit);

    /** Открытое окно турнира в scope (финал: не более одного — индекс). */
    Optional<VotingWindow> findOpenByScope(UUID tournamentId, WindowScope scope);

    /** Последнее окно турнира в scope (нумерация финальных окон). */
    Optional<VotingWindow> findLatestByTournamentIdAndScope(UUID tournamentId, WindowScope scope);

    /** Открытые окна эпохи (кластеры текущего отбора). */
    List<VotingWindow> findOpenByEpochId(UUID epochId);

    /** Открытое окно кластера (лидерборд кластера, алгоритм 9). */
    Optional<VotingWindow> findOpenByClusterId(UUID clusterId);

    /** Сколько открытых окон осталось у эпохи (закрытие эпохи). */
    long countOpenByEpochId(UUID epochId);
```

`TournamentEntryRepository` — добавить методы:

```java
    /** Активное глобальное участие пользователя (допущение 5, раздел 11). */
    Optional<TournamentEntry> findActiveGlobalByUserId(UUID tournamentId, UUID userId);

    /** Участия турнира в статусе (глобальная оркестрация, раздел 8). */
    List<TournamentEntry> findByTournamentIdAndStatus(UUID tournamentId, EntryStatus status);
```

- [ ] **Step 3: Красные контрактные тесты (новые методы)**

В `VotingWindowRepositoryContractTest` добавить (используя существующие билдеры файла; окно квалификации строится через `VotingWindow.openQualification`):

```java
    @Test
    @DisplayName("scope-запросы: due по scope, открытое/последнее по scope, эпоха/кластер")
    void scope_запросы() {
        VotingWindowRepository repository = repository();
        UUID tournamentId = UUID.randomUUID();
        UUID epochId = UUID.randomUUID();
        UUID clusterId = UUID.randomUUID();
        Instant past = Instant.parse("2026-09-27T09:00:00Z");
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        VotingWindow qualification = VotingWindow.openQualification(tournamentId, epochId,
            clusterId, "u4pu", 1, List.of(seed()), past, past.plusSeconds(60), past);
        VotingWindow finalWindow = VotingWindow.openFinal(tournamentId, 1,
            List.of(seed(), seed()), past, past.plusSeconds(60), past);
        repository.save(qualification);
        repository.save(finalWindow);

        assertThat(repository.findDueForCloseByScope(WindowScope.QUALIFICATION, now, 10))
            .containsExactly(qualification.id());
        assertThat(repository.findDueForCloseByScope(WindowScope.FINAL, now, 10))
            .containsExactly(finalWindow.id());
        assertThat(repository.findOpenByScope(tournamentId, WindowScope.FINAL))
            .map(VotingWindow::id).contains(finalWindow.id());
        assertThat(repository.findLatestByTournamentIdAndScope(tournamentId, WindowScope.FINAL))
            .map(VotingWindow::id).contains(finalWindow.id());
        assertThat(repository.findOpenByEpochId(epochId))
            .extracting(VotingWindow::id).containsExactly(qualification.id());
        assertThat(repository.findOpenByClusterId(clusterId))
            .map(VotingWindow::id).contains(qualification.id());
        assertThat(repository.countOpenByEpochId(epochId)).isEqualTo(1);

        qualification.closeQualification(past.plusSeconds(60));
        repository.save(qualification);
        assertThat(repository.countOpenByEpochId(epochId)).isZero();
        assertThat(repository.findOpenByClusterId(clusterId)).isEmpty();
    }
```

(`seed()` — существующий в этом тесте билдер `ParticipantSeed`; если он называется иначе — использовать фактический.)

В `TournamentEntryRepositoryContractTest` добавить:

```java
    @Test
    @DisplayName("глобальные участия: активное по пользователю, по статусу")
    void глобальные_участия() {
        TournamentEntryRepository repository = repository();
        UUID tournamentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        TournamentEntry queued = TournamentEntry.queueForGlobal(tournamentId, userId,
            UUID.randomUUID(), UUID.randomUUID(), now);
        TournamentEntry withdrawn = TournamentEntry.queueForGlobal(tournamentId,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now);
        withdrawn.withdraw();
        repository.save(queued);
        repository.save(withdrawn);

        assertThat(repository.findActiveGlobalByUserId(tournamentId, userId))
            .map(TournamentEntry::id).contains(queued.id());
        assertThat(repository.findActiveGlobalByUserId(tournamentId, UUID.randomUUID()))
            .isEmpty();
        assertThat(repository.findByTournamentIdAndStatus(tournamentId, EntryStatus.QUEUED))
            .extracting(TournamentEntry::id).containsExactly(queued.id());
        assertThat(repository.findByTournamentIdAndStatus(tournamentId, EntryStatus.WITHDRAWN))
            .extracting(TournamentEntry::id).containsExactly(withdrawn.id());
    }
```

Новый контрактный тест `src/test/java/com/plantarena/tournaments/QualificationEpochRepositoryContractTest.java`:

```java
package com.plantarena.tournaments;

import com.plantarena.tournaments.domain.EpochStatus;
import com.plantarena.tournaments.domain.QualificationEpoch;
import com.plantarena.tournaments.domain.QualificationEpochRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Контрактный тест репозитория QualificationEpoch (раздел 14.2). */
@DisplayName("Контракт QualificationEpochRepository")
public abstract class QualificationEpochRepositoryContractTest {

    protected abstract QualificationEpochRepository repository();

    @Test
    @DisplayName("save → findOpenByTournamentId/findLastByTournamentId/findById")
    void сохранение_и_чтение() {
        QualificationEpochRepository repository = repository();
        UUID tournamentId = UUID.randomUUID();
        QualificationEpoch first = epoch(tournamentId, 1);
        repository.save(first);
        QualificationEpoch second = epoch(tournamentId, 2);
        repository.save(second);

        assertThat(repository.findOpenByTournamentId(tournamentId))
            .map(QualificationEpoch::id).contains(first.id());
        assertThat(repository.findLastByTournamentId(tournamentId))
            .map(QualificationEpoch::sequence).contains(2);
        assertThat(repository.findById(first.id())).isPresent();

        first.close(first.closesAt());
        repository.save(first);
        assertThat(repository.findOpenByTournamentId(tournamentId))
            .map(QualificationEpoch::id).contains(second.id());
    }

    private QualificationEpoch epoch(UUID tournamentId, int sequence) {
        Instant opens = Instant.parse("2026-09-27T10:00:00Z");
        return QualificationEpoch.open(UUID.randomUUID(), tournamentId, sequence,
            opens, opens.plusSeconds(3600), opens);
    }
}
```

- [ ] **Step 4: Запустить — красный**

```bash
./mvnw -q test -Dtest='*RepositoryContractTest'
```

Ожидание: FAIL (компиляция): порты без реализаций (JPA и фейки).

- [ ] **Step 5: JPA-реализации**

`VotingWindowJpaEntity` — добавить поля (и геттеры/сеттеры в стиле файла):

```java
    @Column(nullable = false)
    private String scope;

    @Column(name = "epoch_id")
    private UUID epochId;

    @Column(name = "cluster_id")
    private UUID clusterId;

    @Column(name = "cluster_key")
    private String clusterKey;
```

`VotingWindowJpaRepository` — добавить запросы:

```java
    @Query("select w.id from VotingWindowJpaEntity w "
        + "where w.scope = :scope and w.status = 'OPEN' and w.closesAt <= :now "
        + "order by w.closesAt")
    List<UUID> findDueForCloseByScope(@Param("scope") String scope, @Param("now") Instant now,
                                      Pageable pageable);

    Optional<VotingWindowJpaEntity> findFirstByTournamentIdAndScopeAndStatusOrderBySequenceDesc(
        UUID tournamentId, String scope, String status);

    Optional<VotingWindowJpaEntity> findFirstByTournamentIdAndScopeOrderBySequenceDesc(
        UUID tournamentId, String scope);

    List<VotingWindowJpaEntity> findByEpochIdAndStatusOrderByClusterKeyAsc(UUID epochId,
                                                                           String status);

    Optional<VotingWindowJpaEntity> findByClusterIdAndStatus(UUID clusterId, String status);

    long countByEpochIdAndStatus(UUID epochId, String status);
```

`JpaVotingWindowRepository` — реализация новых методов порта + честный маппинг scope:

```java
    @Override
    @Transactional(readOnly = true)
    public List<UUID> findDueForCloseByScope(WindowScope scope, Instant now, int limit) {
        return jpaRepository.findDueForCloseByScope(scope.name(), now, PageRequest.of(0, limit));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VotingWindow> findOpenByScope(UUID tournamentId, WindowScope scope) {
        return jpaRepository
            .findFirstByTournamentIdAndScopeAndStatusOrderBySequenceDesc(
                tournamentId, scope.name(), WindowStatus.OPEN.name())
            .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VotingWindow> findLatestByTournamentIdAndScope(UUID tournamentId,
                                                                   WindowScope scope) {
        return jpaRepository.findFirstByTournamentIdAndScopeOrderBySequenceDesc(
                tournamentId, scope.name())
            .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VotingWindow> findOpenByEpochId(UUID epochId) {
        return jpaRepository.findByEpochIdAndStatusOrderByClusterKeyAsc(
                epochId, WindowStatus.OPEN.name())
            .stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VotingWindow> findOpenByClusterId(UUID clusterId) {
        return jpaRepository.findByClusterIdAndStatus(clusterId, WindowStatus.OPEN.name())
            .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public long countOpenByEpochId(UUID epochId) {
        return jpaRepository.countByEpochIdAndStatus(epochId, WindowStatus.OPEN.name());
    }
```

и в `mapState` / `toDomain` заменить литералы Task 4 на реальные поля:

```java
        entity.setScope(window.scope().name());
        entity.setEpochId(window.epochId());
        entity.setClusterId(window.clusterId());
        entity.setClusterKey(window.clusterKey());
```

```java
        return VotingWindow.restore(entity.getId(), entity.getTournamentId(),
            entity.getSequence(), WindowScope.valueOf(entity.getScope()),
            entity.getEpochId(), entity.getClusterId(), entity.getClusterKey(),
            WindowStatus.valueOf(entity.getStatus()),
            entity.getOpensAt(), entity.getClosesAt(), entity.getCreatedAt(),
            entity.getVersion(), participants, votes);
```

`TournamentEntryJpaRepository` — добавить:

```java
    @org.springframework.data.jpa.repository.Query("select e from TournamentEntryJpaEntity e "
        + "where e.tournamentId = :tournamentId and e.userId = :userId "
        + "and e.status in ('QUEUED', 'QUALIFYING', 'FINAL_PENDING', 'FINALIST')")
    java.util.Optional<TournamentEntryJpaEntity> findActiveGlobal(@Param("tournamentId") UUID tournamentId,
                                                                  @Param("userId") UUID userId);

    java.util.List<TournamentEntryJpaEntity> findByTournamentIdAndStatus(UUID tournamentId,
                                                                        String status);
```

`JpaTournamentEntryRepository` — реализация порта (маппинг `TournamentEntry.restore` по образцу существующего `findById`):

```java
    @Override
    @Transactional(readOnly = true)
    public Optional<TournamentEntry> findActiveGlobalByUserId(UUID tournamentId, UUID userId) {
        return jpaRepository.findActiveGlobal(tournamentId, userId).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TournamentEntry> findByTournamentIdAndStatus(UUID tournamentId,
                                                             EntryStatus status) {
        return jpaRepository.findByTournamentIdAndStatus(tournamentId, status.name())
            .stream().map(this::toDomain).toList();
    }
```

(если `JpaTournamentEntryRepository` не имеет приватного `toDomain` — извлечь по образцу `JpaVotingWindowRepository.toDomain`, маппинг `TournamentEntry.restore(...)`.)

`QualificationEpochJpaEntity`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** JPA-модель эпохи отбора (раздел 11); маппинг в домен — явный. */
@Entity
@Table(name = "qualification_epoch", schema = "tournaments")
public class QualificationEpochJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tournament_id", nullable = false)
    private UUID tournamentId;

    @Column(nullable = false)
    private int sequence;

    @Column(nullable = false)
    private String status;

    @Column(name = "opens_at", nullable = false)
    private Instant opensAt;

    @Column(name = "closes_at", nullable = false)
    private Instant closesAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private Long version;

    UUID getId() {
        return id;
    }

    UUID getTournamentId() {
        return tournamentId;
    }

    int getSequence() {
        return sequence;
    }

    String getStatus() {
        return status;
    }

    Instant getOpensAt() {
        return opensAt;
    }

    Instant getClosesAt() {
        return closesAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    long getVersion() {
        return version;
    }

    void setId(UUID id) {
        this.id = id;
    }

    void setTournamentId(UUID tournamentId) {
        this.tournamentId = tournamentId;
    }

    void setSequence(int sequence) {
        this.sequence = sequence;
    }

    void setStatus(String status) {
        this.status = status;
    }

    void setOpensAt(Instant opensAt) {
        this.opensAt = opensAt;
    }

    void setClosesAt(Instant closesAt) {
        this.closesAt = closesAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
```

`QualificationEpochJpaRepository`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data для qualification_epoch. */
public interface QualificationEpochJpaRepository
        extends JpaRepository<QualificationEpochJpaEntity, UUID> {

    Optional<QualificationEpochJpaEntity> findFirstByTournamentIdAndStatusOrderBySequenceDesc(
        UUID tournamentId, String status);

    Optional<QualificationEpochJpaEntity> findFirstByTournamentIdOrderBySequenceDesc(
        UUID tournamentId);
}
```

`JpaQualificationEpochRepository`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.EpochStatus;
import com.plantarena.tournaments.domain.QualificationEpoch;
import com.plantarena.tournaments.domain.QualificationEpochRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA-реализация порта QualificationEpochRepository: явный маппинг. */
@Repository
public class JpaQualificationEpochRepository implements QualificationEpochRepository {

    private final QualificationEpochJpaRepository jpaRepository;

    public JpaQualificationEpochRepository(QualificationEpochJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public QualificationEpoch save(QualificationEpoch epoch) {
        QualificationEpochJpaEntity entity = jpaRepository.findById(epoch.id())
            .orElseGet(() -> newEntity(epoch));
        entity.setTournamentId(epoch.tournamentId());
        entity.setSequence(epoch.sequence());
        entity.setStatus(epoch.status().name());
        entity.setOpensAt(epoch.opensAt());
        entity.setClosesAt(epoch.closesAt());
        entity.setCreatedAt(epoch.createdAt());
        jpaRepository.saveAndFlush(entity);
        return epoch;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QualificationEpoch> findById(UUID id) {
        return jpaRepository.findById(id).map(JpaQualificationEpochRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QualificationEpoch> findOpenByTournamentId(UUID tournamentId) {
        return jpaRepository
            .findFirstByTournamentIdAndStatusOrderBySequenceDesc(
                tournamentId, EpochStatus.OPEN.name())
            .map(JpaQualificationEpochRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QualificationEpoch> findLastByTournamentId(UUID tournamentId) {
        return jpaRepository.findFirstByTournamentIdOrderBySequenceDesc(tournamentId)
            .map(JpaQualificationEpochRepository::toDomain);
    }

    private QualificationEpochJpaEntity newEntity(QualificationEpoch epoch) {
        QualificationEpochJpaEntity entity = new QualificationEpochJpaEntity();
        entity.setId(epoch.id());
        return entity;
    }

    private static QualificationEpoch toDomain(QualificationEpochJpaEntity entity) {
        return QualificationEpoch.restore(entity.getId(), entity.getTournamentId(),
            entity.getSequence(), EpochStatus.valueOf(entity.getStatus()),
            entity.getOpensAt(), entity.getClosesAt(), entity.getCreatedAt(),
            entity.getVersion());
    }
}
```

- [ ] **Step 6: Фейки + сервисные правки**

`InMemoryVotingWindowRepository` — реализовать новые методы порта:

```java
    @Override
    public List<UUID> findDueForCloseByScope(WindowScope scope, Instant now, int limit) {
        return windows.values().stream()
            .filter(window -> window.scope() == scope
                && window.status() == WindowStatus.OPEN
                && !now.isBefore(window.closesAt()))
            .sorted(Comparator.comparing(VotingWindow::closesAt))
            .limit(limit)
            .map(VotingWindow::id)
            .toList();
    }

    @Override
    public Optional<VotingWindow> findOpenByScope(UUID tournamentId, WindowScope scope) {
        return windows.values().stream()
            .filter(window -> window.tournamentId().equals(tournamentId)
                && window.scope() == scope && window.status() == WindowStatus.OPEN)
            .max(Comparator.comparingInt(VotingWindow::sequence));
    }

    @Override
    public Optional<VotingWindow> findLatestByTournamentIdAndScope(UUID tournamentId,
                                                                   WindowScope scope) {
        return windows.values().stream()
            .filter(window -> window.tournamentId().equals(tournamentId)
                && window.scope() == scope)
            .max(Comparator.comparingInt(VotingWindow::sequence));
    }

    @Override
    public List<VotingWindow> findOpenByEpochId(UUID epochId) {
        return windows.values().stream()
            .filter(window -> epochId.equals(window.epochId())
                && window.status() == WindowStatus.OPEN)
            .sorted(Comparator.comparing(w -> w.clusterKey() == null ? "" : w.clusterKey()))
            .toList();
    }

    @Override
    public Optional<VotingWindow> findOpenByClusterId(UUID clusterId) {
        return windows.values().stream()
            .filter(window -> clusterId.equals(window.clusterId())
                && window.status() == WindowStatus.OPEN)
            .findAny();
    }

    @Override
    public long countOpenByEpochId(UUID epochId) {
        return windows.values().stream()
            .filter(window -> epochId.equals(window.epochId())
                && window.status() == WindowStatus.OPEN)
            .count();
    }
```

`InMemoryTournamentEntryRepository` — реализовать новые методы порта:

```java
    private static final Set<EntryStatus> ACTIVE_GLOBAL = Set.of(EntryStatus.QUEUED,
        EntryStatus.QUALIFYING, EntryStatus.FINAL_PENDING, EntryStatus.FINALIST);

    @Override
    public Optional<TournamentEntry> findActiveGlobalByUserId(UUID tournamentId, UUID userId) {
        return entries.values().stream()
            .filter(entry -> entry.tournamentId().equals(tournamentId)
                && entry.userId().equals(userId)
                && ACTIVE_GLOBAL.contains(entry.status()))
            .findAny();
    }

    @Override
    public List<TournamentEntry> findByTournamentIdAndStatus(UUID tournamentId,
                                                             EntryStatus status) {
        return entries.values().stream()
            .filter(entry -> entry.tournamentId().equals(tournamentId)
                && entry.status() == status)
            .sorted(Comparator.comparing(TournamentEntry::joinedAt)
                .thenComparing(TournamentEntry::id))
            .toList();
    }
```

(`entries` — фактическое поле хранилища фейка; импорты `EntryStatus`, `Comparator`, `Set`.)

`CloseVotingWindowService.closeOne(...)` — первой строкой метода (сразу после загрузки окна):

```java
        if (window.scope() != WindowScope.PRIVATE) {
            return; // глобальные окна закрывает AdvanceGlobalCompetitionService (раздел 8)
        }
```

(+ импорт `WindowScope`; в `CloseVotingWindowServiceTest` добавить кейс «глобальное окно не закрывается приватным use case'ом» — окно `openFinal`, вызов `closeDue`, утверждение: окно осталось OPEN.)

`TournamentJpaRepository` — в оба запроса `search`/`searchCount` добавить условие (первой строкой where):

```java
        t.type = 'PRIVATE'
```

т.е. `where t.type = 'PRIVATE' and (:admin = true or ...)`.

`TournamentQueryService.get(...)` — скрыть глобальный турнир (первой строкой метода `get`):

```java
        Tournament tournament = find(tournamentId);
        if (tournament.type() == TournamentType.GLOBAL) {
            throw new TournamentNotFoundException("Турнир не найден: " + tournamentId);
        }
```

(+ импорт `TournamentType`; в `TournamentQueryServiceTest` — кейс «глобальный турнир скрыт из списка и просмотра» с фейком, хранящим `Tournament.global(...)`.)

- [ ] **Step 7: Запустить — зелёный + полный verify**

```bash
./mvnw -q test -Dtest='*RepositoryContractTest,CloseVotingWindowServiceTest,TournamentQueryServiceTest'
./mvnw -q verify
```

Ожидание: PASS; миграция V4 применяется к существующим данным (DEFAULT 'PRIVATE' заполняет scope).

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/tournaments/V4__global.sql \
  src/main/java/com/plantarena/tournaments src/test/java/com/plantarena/tournaments
git commit -m "feat(tournaments): V4 — scope окон, эпохи, глобальные участия; скрытие GLOBAL из /tournaments"
```

---

### Task 6: application — подача/снятие, `AdvanceGlobalCompetition`, запросы; out-адаптеры; identity location

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/application/GlobalCompetitionId.java`
- Create: `src/main/java/com/plantarena/tournaments/application/GlobalCompetitionSettings.java`
- Create: `src/main/java/com/plantarena/config/TournamentsWiringConfig.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/in/SubmitGlobalEntryUseCase.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/in/WithdrawGlobalEntryUseCase.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/in/GetMyGlobalEntryUseCase.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/in/AdvanceGlobalCompetitionUseCase.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/in/EnsureGlobalCompetitionUseCase.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/in/GetGlobalInfoUseCase.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/in/ListGlobalClustersUseCase.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/in/GetGlobalLeaderboardUseCase.java`
- Create: `src/main/java/com/plantarena/tournaments/application/SubmitGlobalEntryService.java`
- Create: `src/main/java/com/plantarena/tournaments/application/WithdrawGlobalEntryService.java`
- Create: `src/main/java/com/plantarena/tournaments/application/AdvanceGlobalCompetitionService.java`
- Create: `src/main/java/com/plantarena/tournaments/application/GlobalQueryService.java`
- Create: `src/main/java/com/plantarena/tournaments/application/EnsureGlobalCompetitionService.java`
- Create: `src/main/java/com/plantarena/tournaments/application/{SubmittedPlantNotFoundException, PlantNotApprovedException, LocationRequiredException, ActiveGlobalEntryExistsException, GlobalEntryNotFoundException, GlobalEntryNotWithdrawableException, ClusterNotFoundException, UnknownGlobalScopeException}.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/identity/InProcessParticipantLocations.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/geo/InProcessClusteringGateway.java`
- Modify: `src/main/java/com/plantarena/identity/api/UserDirectory.java`
- Modify: `src/main/java/com/plantarena/identity/application/UserDirectoryFacade.java`
- Test: `src/test/java/com/plantarena/tournaments/application/SubmitGlobalEntryServiceTest.java`
- Test: `src/test/java/com/plantarena/tournaments/application/WithdrawGlobalEntryServiceTest.java`
- Test: `src/test/java/com/plantarena/tournaments/application/AdvanceGlobalCompetitionServiceTest.java`
- Test: `src/test/java/com/plantarena/tournaments/application/GlobalQueryServiceTest.java`
- Test: `src/test/java/com/plantarena/tournaments/application/support/FakeParticipantLocationsGateway.java`
- Test: `src/test/java/com/plantarena/tournaments/application/support/FakeClusteringGateway.java`
- Test: `src/test/java/com/plantarena/tournaments/application/support/InMemoryQualificationEpochRepository.java`
- Test: `src/test/java/com/plantarena/tournaments/application/support/InMemoryQualificationEpochRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/tournaments/adapter/out/persistence/JpaQualificationEpochRepositoryContractIT.java`
- Test: `src/test/java/com/plantarena/identity/application/UserDirectoryFacadeTest.java`

**Interfaces:**
- Consumes: домен/порты Tasks 4–5; `PlantEligibilityGateway` (`reserveSubmission(ownerId, plantId, idempotencyKey)`, `release(reservationId)` — существует), `PlantLifecycleGateway.registerDeath(plantId, kind, cooldownExpiresAt, reason, sourceEntryId)` — существует, `IntegrationEventPublisher`, `EntryEliminatedEvent`, `TournamentsAccessPolicy`, `TransactionTemplate` (бин существует), `FakePlantLifecycleGateway`/`FakePlantEligibilityGateway`/`FakePlantDirectoryGateway`/`FakeEventPublisher` (существуют).
- Produces (для Task 7): `GlobalCompetitionId.VALUE = UUID 00000007-10ba-4000-8000-000000000001`; `GlobalCompetitionSettings(Duration epochDuration, Duration finalWindowDuration)` (`plantarena.global.*`); use case-порты с DTO: `SubmitGlobalEntryUseCase { GlobalEntryView submit(CurrentActor, UUID plantId) }`, `WithdrawGlobalEntryUseCase { void withdraw(CurrentActor, UUID entryId) }`, `GetMyGlobalEntryUseCase { Optional<GlobalEntryView> findActive(CurrentActor) }` с `record GlobalEntryView(UUID id, UUID plantId, String status, Instant joinedAt)`; `AdvanceGlobalCompetitionUseCase { AdvanceReport advance(Instant now); record AdvanceReport(int qualificationClosed, int finalClosed, int finalsOpened, int epochsOpened) }`; `EnsureGlobalCompetitionUseCase { void ensure() }`; `GetGlobalInfoUseCase { GlobalInfo globalInfo(); record GlobalInfo(long epochDurationSeconds, long finalWindowDurationSeconds, EpochInfo currentEpoch, WindowInfo currentFinalWindow); record EpochInfo(UUID id, int sequence, Instant opensAt, Instant closesAt); record WindowInfo(UUID id, int sequence, Instant opensAt, Instant closesAt) }`; `ListGlobalClustersUseCase { ClusterListResult list(PaginationParams); record ClusterListResult(List<GlobalCluster> items, long total); record GlobalCluster(UUID clusterId, String clusterKey, UUID windowId, int memberCount, Instant closesAt) }`; `GetGlobalLeaderboardUseCase { GlobalLeaderboard finalLeaderboard(PaginationParams); GlobalLeaderboard clusterLeaderboard(UUID clusterId, PaginationParams); record GlobalLeaderboard(String scope, UUID windowId, Instant closesAt, Instant asOf, List<Item> items); record Item(UUID entryId, UUID userId, long score) }`.

- [ ] **Step 1: identity — координаты через OHS**

`src/main/java/com/plantarena/identity/api/UserDirectory.java` — заменить целиком:

```java
package com.plantarena.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Опубликованный контракт identity (Open Host Service, раздел 4.3): публичный
 * профиль и координаты для внутренних потребителей (tournaments — гео-
 * кластеризация, итерация 7). email и passwordHash не раскрываются; точные
 * координаты наружу через REST не публикуются — только через этот контракт.
 */
public interface UserDirectory {

    Optional<UserData> findById(UUID userId);

    /** Координаты профиля и версия (locationVersion) на момент чтения. */
    Optional<UserLocation> findLocation(UUID userId);

    /** Публичный профиль: id, имя, активность. */
    record UserData(UUID id, String displayName, boolean active) {
    }

    /** Координаты профиля (раздел 8: для глобального участия обязательны). */
    record UserLocation(double latitude, double longitude, long locationVersion) {
    }
}
```

`UserDirectoryFacade` — добавить реализацию:

```java
    @Override
    public Optional<UserLocation> findLocation(UUID userId) {
        return users.findById(userId)
            .flatMap(user -> Optional.ofNullable(user.location()))
            .map(location -> new UserLocation(location.latitude(), location.longitude(),
                users.findById(userId).orElseThrow().version()));
    }
```

(если `UserRepository.findById` возвращает `Optional<User>` — как есть; иначе адаптировать к фактической сигнатуре. В `UserDirectoryFacadeTest` добавить кейс: пользователь с координатами → `findLocation` возвращает их и версию; без координат — пустой Optional.)

- [ ] **Step 2: Константа, настройки, исключения**

`src/main/java/com/plantarena/tournaments/application/GlobalCompetitionId.java`:

```java
package com.plantarena.tournaments.application;

import java.util.UUID;

/** Фиксированный id единственного глобального турнира (раздел 8, ADR-012). */
public final class GlobalCompetitionId {

    public static final UUID VALUE = UUID.fromString("00000007-10ba-4000-8000-000000000001");

    private GlobalCompetitionId() {
    }
}
```

`src/main/java/com/plantarena/tournaments/application/GlobalCompetitionSettings.java`:

```java
package com.plantarena.tournaments.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Тайминги глобального турнира (раздел 8): эпоха и финальное окно. */
@ConfigurationProperties(prefix = "plantarena.global")
public record GlobalCompetitionSettings(Duration epochDuration, Duration finalWindowDuration) {

    public GlobalCompetitionSettings {
        if (epochDuration == null || epochDuration.isNegative() || epochDuration.isZero()) {
            throw new IllegalArgumentException("plantarena.global.epoch-duration > 0");
        }
        if (finalWindowDuration == null || finalWindowDuration.isNegative()
                || finalWindowDuration.isZero()) {
            throw new IllegalArgumentException("plantarena.global.final-window-duration > 0");
        }
    }
}
```

`src/main/java/com/plantarena/config/TournamentsWiringConfig.java`:

```java
package com.plantarena.config;

import com.plantarena.tournaments.application.GlobalCompetitionSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Связывание контекста tournaments: настройки глобального турнира. */
@Configuration
@EnableConfigurationProperties(GlobalCompetitionSettings.class)
public class TournamentsWiringConfig {
}
```

Значения по умолчанию — в `application.yml` (по существующему паттерну конфигурации):

```yaml
plantarena:
  global:
    epoch-duration: 24h
    final-window-duration: 6h
```

Исключения (все — по образцу `VotingClosedException`: `public final class X extends RuntimeException { public X(String message) { super(message); } }`), файл на класс:

- `SubmittedPlantNotFoundException` — 404 PLANT_NOT_FOUND
- `PlantNotApprovedException` — 409 PLANT_NOT_APPROVED
- `LocationRequiredException` — 409 LOCATION_REQUIRED
- `ActiveGlobalEntryExistsException` — 409 GLOBAL_ENTRY_ACTIVE
- `GlobalEntryNotFoundException` — 404 GLOBAL_ENTRY_NOT_FOUND
- `GlobalEntryNotWithdrawableException` — 409 ENTRY_IN_WINDOW
- `ClusterNotFoundException` — 404 CLUSTER_NOT_FOUND
- `UnknownGlobalScopeException` — 400 GLOBAL_SCOPE_UNKNOWN

- [ ] **Step 3: Порты in (use case-интерфейсы с DTO)**

`SubmitGlobalEntryUseCase`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.time.Instant;
import java.util.UUID;

/** Подача заявки в глобальный турнир (раздел 8, алгоритм 1). */
public interface SubmitGlobalEntryUseCase {

    GlobalEntryView submit(CurrentActor actor, UUID plantId);

    /** Созданное глобальное участие. */
    record GlobalEntryView(UUID id, UUID plantId, String status, Instant joinedAt) {
    }
}
```

`WithdrawGlobalEntryUseCase`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/** Снятие заявки из очереди глобального турнира (раздел 13). */
public interface WithdrawGlobalEntryUseCase {

    void withdraw(CurrentActor actor, UUID entryId);
}
```

`GetMyGlobalEntryUseCase`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.SubmitGlobalEntryUseCase.GlobalEntryView;
import java.util.Optional;

/** Текущее активное глобальное участие пользователя (раздел 13). */
public interface GetMyGlobalEntryUseCase {

    Optional<GlobalEntryView> findActive(CurrentActor actor);
}
```

`AdvanceGlobalCompetitionUseCase`:

```java
package com.plantarena.tournaments.application.port.in;

import java.time.Instant;

/**
 * Продвижение границ глобального турнира (раздел 8, алгоритм 6): один use
 * case для scheduler'а и demo-ручки. Фиксированный порядок: закрыть
 * квалификацию → закрыть финал → открыть следующий финал → открыть
 * следующую эпоху. Идемпотентен по статусам (рестарт без повторной гибели).
 */
public interface AdvanceGlobalCompetitionUseCase {

    AdvanceReport advance(Instant now);

    /** Счётчики выполненных шагов (диагностика demo-ручки). */
    record AdvanceReport(int qualificationClosed, int finalClosed, int finalsOpened,
                         int epochsOpened) {
    }
}
```

`EnsureGlobalCompetitionUseCase`:

```java
package com.plantarena.tournaments.application.port.in;

/** Идемпотентное создание единственной записи глобального турнира (ADR-012). */
public interface EnsureGlobalCompetitionUseCase {

    void ensure();
}
```

`GetGlobalInfoUseCase`:

```java
package com.plantarena.tournaments.application.port.in;

import java.time.Instant;
import java.util.UUID;

/** Публичная конфигурация и текущие окна глобального турнира (раздел 13). */
public interface GetGlobalInfoUseCase {

    GlobalInfo globalInfo();

    record GlobalInfo(long epochDurationSeconds, long finalWindowDurationSeconds,
                      EpochInfo currentEpoch, WindowInfo currentFinalWindow) {
    }

    record EpochInfo(UUID id, int sequence, Instant opensAt, Instant closesAt) {
    }

    record WindowInfo(UUID id, int sequence, Instant opensAt, Instant closesAt) {
    }
}
```

`ListGlobalClustersUseCase`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.web.PaginationParams;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Кластеры текущей эпохи (раздел 13): публично, без приватных данных. */
public interface ListGlobalClustersUseCase {

    ClusterListResult list(PaginationParams page);

    record ClusterListResult(List<GlobalCluster> items, long total) {
    }

    record GlobalCluster(UUID clusterId, String clusterKey, UUID windowId, int memberCount,
                         Instant closesAt) {
    }
}
```

`GetGlobalLeaderboardUseCase`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.web.PaginationParams;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Лидерборды глобального турнира (раздел 8, алгоритм 9): всегда scope,
 * windowId, closesAt, asOf; очки разных окон/кластеров не смешиваются.
 */
public interface GetGlobalLeaderboardUseCase {

    GlobalLeaderboard finalLeaderboard(PaginationParams page);

    GlobalLeaderboard clusterLeaderboard(UUID clusterId, PaginationParams page);

    record GlobalLeaderboard(String scope, UUID windowId, Instant closesAt, Instant asOf,
                             List<Item> items) {
    }

    record Item(UUID entryId, UUID userId, long score) {
    }
}
```

- [ ] **Step 4: Красные application-тесты**

`src/test/java/com/plantarena/tournaments/application/support/FakeParticipantLocationsGateway.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.application.port.out.ParticipantLocationsGateway;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Фейк порта координат: координаты задаются тестом. */
public class FakeParticipantLocationsGateway implements ParticipantLocationsGateway {

    public final Map<UUID, UserLocation> locations = new java.util.HashMap<>();

    @Override
    public Optional<UserLocation> findLocation(UUID userId) {
        return Optional.ofNullable(locations.get(userId));
    }
}
```

`src/test/java/com/plantarena/tournaments/application/support/FakeClusteringGateway.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.application.port.out.ClusteringGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Фейк порта кластеризации: группирует по точным координатам (одна точка — один кластер). */
public class FakeClusteringGateway implements ClusteringGateway {

    public final Map<UUID, AssignedCluster> clustersByEpoch = new ConcurrentHashMap<>();

    @Override
    public List<AssignedCluster> assignClusters(UUID epochId, List<MemberLocation> members) {
        List<AssignedCluster> result = new ArrayList<>();
        Map<String, List<UUID>> byPoint = new java.util.TreeMap<>();
        for (MemberLocation member : members) {
            byPoint.computeIfAbsent(member.latitude() + ":" + member.longitude(),
                key -> new ArrayList<>()).add(member.entryId());
        }
        for (Map.Entry<String, List<UUID>> entry : byPoint.entrySet()) {
            AssignedCluster cluster = new AssignedCluster(UUID.randomUUID(),
                "fake-" + entry.getKey().replace(':', '-'), List.copyOf(entry.getValue()));
            result.add(cluster);
        }
        clustersByEpoch.put(epochId, null); // факт вызова
        clustersByEpoch.remove(epochId);
        clustersByEpoch.put(epochId, result.isEmpty() ? null : result.get(0));
        called = true;
        return result;
    }

    public boolean called = false;
}
```

(упростить: поле `public boolean called;` + возврат списка — убрать промежуточные put/remove:

```java
    public boolean called = false;

    @Override
    public List<AssignedCluster> assignClusters(UUID epochId, List<MemberLocation> members) {
        called = true;
        List<AssignedCluster> result = new ArrayList<>();
        Map<String, List<UUID>> byPoint = new java.util.TreeMap<>();
        for (MemberLocation member : members) {
            byPoint.computeIfAbsent(member.latitude() + ":" + member.longitude(),
                key -> new ArrayList<>()).add(member.entryId());
        }
        for (Map.Entry<String, List<UUID>> entry : byPoint.entrySet()) {
            result.add(new AssignedCluster(UUID.randomUUID(),
                "fake-" + entry.getKey().replace(':', '-'), List.copyOf(entry.getValue())));
        }
        return result;
    }
```

)

`src/test/java/com/plantarena/tournaments/application/support/InMemoryQualificationEpochRepository.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.domain.EpochStatus;
import com.plantarena.tournaments.domain.QualificationEpoch;
import com.plantarena.tournaments.domain.QualificationEpochRepository;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory фейк QualificationEpochRepository. */
public class InMemoryQualificationEpochRepository implements QualificationEpochRepository {

    public final Map<UUID, QualificationEpoch> epochs = new ConcurrentHashMap<>();

    @Override
    public QualificationEpoch save(QualificationEpoch epoch) {
        epochs.put(epoch.id(), epoch);
        return epoch;
    }

    @Override
    public Optional<QualificationEpoch> findById(UUID id) {
        return Optional.ofNullable(epochs.get(id));
    }

    @Override
    public Optional<QualificationEpoch> findOpenByTournamentId(UUID tournamentId) {
        return epochs.values().stream()
            .filter(epoch -> epoch.tournamentId().equals(tournamentId)
                && epoch.status() == EpochStatus.OPEN)
            .max(Comparator.comparingInt(QualificationEpoch::sequence));
    }

    @Override
    public Optional<QualificationEpoch> findLastByTournamentId(UUID tournamentId) {
        return epochs.values().stream()
            .filter(epoch -> epoch.tournamentId().equals(tournamentId))
            .max(Comparator.comparingInt(QualificationEpoch::sequence));
    }
}
```

`src/test/java/com/plantarena/tournaments/application/support/InMemoryQualificationEpochRepositoryContractTest.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.QualificationEpochRepositoryContractTest;
import com.plantarena.tournaments.domain.QualificationEpochRepository;

class InMemoryQualificationEpochRepositoryContractTest
        extends QualificationEpochRepositoryContractTest {

    private final InMemoryQualificationEpochRepository repository =
        new InMemoryQualificationEpochRepository();

    @Override
    protected QualificationEpochRepository repository() {
        return repository;
    }
}
```

`src/test/java/com/plantarena/tournaments/adapter/out/persistence/JpaQualificationEpochRepositoryContractIT.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.QualificationEpochRepositoryContractTest;
import com.plantarena.tournaments.domain.QualificationEpochRepository;
import org.springframework.beans.factory.annotation.Autowired;

class JpaQualificationEpochRepositoryContractIT
        extends QualificationEpochRepositoryContractTest {

    @Autowired
    private JpaQualificationEpochRepository repository;

    @Override
    protected QualificationEpochRepository repository() {
        return repository;
    }
}
```

`src/test/java/com/plantarena/tournaments/application/SubmitGlobalEntryServiceTest.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.out.ParticipantLocationsGateway.UserLocation;
import com.plantarena.tournaments.application.port.out.PlantDirectoryGateway.PlantSnapshot;
import com.plantarena.tournaments.application.support.FakeParticipantLocationsGateway;
import com.plantarena.tournaments.application.support.FakePlantDirectoryGateway;
import com.plantarena.tournaments.application.support.FakePlantEligibilityGateway;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.TournamentEntry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Подача глобальной заявки (раздел 8, алгоритм 1; раздел 6 — только APPROVED). */
@DisplayName("SubmitGlobalEntryService: очередь глобального турнира")
class SubmitGlobalEntryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final FakePlantDirectoryGateway plants = new FakePlantDirectoryGateway();
    private final FakeParticipantLocationsGateway locations = new FakeParticipantLocationsGateway();
    private final FakePlantEligibilityGateway eligibility = new FakePlantEligibilityGateway();
    private final InMemoryTournamentEntryRepository entries = new InMemoryTournamentEntryRepository();
    private final SubmitGlobalEntryService service = new SubmitGlobalEntryService(
        plants, locations, eligibility, entries, new TournamentsAccessPolicy(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        new org.springframework.transaction.support.TransactionTemplate());

    @Test
    @DisplayName("одобренное растение + координаты → QUEUED, резерв с idempotency key = entryId")
    void подача_в_очередь() {
        UUID user = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        plants.put(new PlantSnapshot(plantId, user, true));
        locations.locations.put(user, new UserLocation(55.7558, 37.6173, 1L));

        SubmitGlobalEntryUseCase.GlobalEntryView view = service.submit(actor(user), plantId);

        assertThat(view.status()).isEqualTo("QUEUED");
        TournamentEntry entry = entries.findById(view.id()).orElseThrow();
        assertThat(entry.status()).isEqualTo(EntryStatus.QUEUED);
        assertThat(eligibility.reservedIds).contains(entry.reservationId());
        assertThat(eligibility.lastIdempotencyKey).isEqualTo(view.id());
    }

    @Test
    @DisplayName("не одобренное растение — 409 PLANT_NOT_APPROVED (раздел 6)")
    void не_одобренное() {
        UUID user = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        plants.put(new PlantSnapshot(plantId, user, false));
        locations.locations.put(user, new UserLocation(55.7558, 37.6173, 1L));
        assertThatThrownBy(() -> service.submit(actor(user), plantId))
            .isInstanceOf(PlantNotApprovedException.class);
    }

    @Test
    @DisplayName("без координат — 409 LOCATION_REQUIRED (раздел 8)")
    void без_координат() {
        UUID user = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        plants.put(new PlantSnapshot(plantId, user, true));
        assertThatThrownBy(() -> service.submit(actor(user), plantId))
            .isInstanceOf(LocationRequiredException.class);
    }

    @Test
    @DisplayName("второе активное участие — 409 GLOBAL_ENTRY_ACTIVE (допущение 5)")
    void второе_активное() {
        UUID user = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        plants.put(new PlantSnapshot(first, user, true));
        plants.put(new PlantSnapshot(second, user, true));
        locations.locations.put(user, new UserLocation(55.7558, 37.6173, 1L));
        service.submit(actor(user), first);
        assertThatThrownBy(() -> service.submit(actor(user), second))
            .isInstanceOf(ActiveGlobalEntryExistsException.class);
    }

    @Test
    @DisplayName("чужое/несуществующее растение — 404 (скрыто)")
    void чужое_растение() {
        UUID user = UUID.randomUUID();
        UUID plantId = UUID.randomUUID();
        plants.put(new PlantSnapshot(plantId, UUID.randomUUID(), true));
        locations.locations.put(user, new UserLocation(55.7558, 37.6173, 1L));
        assertThatThrownBy(() -> service.submit(actor(user), plantId))
            .isInstanceOf(SubmittedPlantNotFoundException.class);
        assertThatThrownBy(() -> service.submit(actor(user), UUID.randomUUID()))
            .isInstanceOf(SubmittedPlantNotFoundException.class);
    }

    private CurrentActor actor(UUID userId) {
        return new CurrentActor(userId, Set.of(AppRole.USER), false);
    }
}
```

(если `FakePlantDirectoryGateway` не имеет метода `put` — добавить его: `public void put(PlantSnapshot snapshot) { snapshots.put(snapshot.plantId(), snapshot); }` по фактическому устройству фейка; конструктор `CurrentActor` — по фактическому.)

`src/test/java/com/plantarena/tournaments/application/WithdrawGlobalEntryServiceTest.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.support.FakePlantEligibilityGateway;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.TournamentEntry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Снятие заявки из очереди (раздел 13): только владелец и только QUEUED. */
@DisplayName("WithdrawGlobalEntryService: снятие из очереди")
class WithdrawGlobalEntryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final FakePlantEligibilityGateway eligibility = new FakePlantEligibilityGateway();
    private final InMemoryTournamentEntryRepository entries = new InMemoryTournamentEntryRepository();
    private final WithdrawGlobalEntryService service = new WithdrawGlobalEntryService(
        entries, eligibility, new TournamentsAccessPolicy(),
        Clock.fixed(NOW, ZoneOffset.UTC),
        new org.springframework.transaction.support.TransactionTemplate());

    @Test
    @DisplayName("QUEUED → WITHDRAWN, резерв освобождается")
    void снятие() {
        TournamentEntry entry = queued();
        entries.save(entry);
        service.withdraw(actor(entry.userId()), entry.id());
        assertThat(entries.findById(entry.id()).orElseThrow().status())
            .isEqualTo(EntryStatus.WITHDRAWN);
        assertThat(eligibility.releasedIds).contains(entry.reservationId());
    }

    @Test
    @DisplayName("не из очереди — 409 ENTRY_IN_WINDOW")
    void не_из_очереди() {
        TournamentEntry entry = queued();
        entry.startQualifying();
        entries.save(entry);
        assertThatThrownBy(() -> service.withdraw(actor(entry.userId()), entry.id()))
            .isInstanceOf(GlobalEntryNotWithdrawableException.class);
    }

    @Test
    @DisplayName("чужая заявка скрыта — 404")
    void чужая_заявка() {
        TournamentEntry entry = queued();
        entries.save(entry);
        assertThatThrownBy(() -> service.withdraw(actor(UUID.randomUUID()), entry.id()))
            .isInstanceOf(GlobalEntryNotFoundException.class);
    }

    private TournamentEntry queued() {
        return TournamentEntry.queueForGlobal(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), NOW);
    }

    private CurrentActor actor(UUID userId) {
        return new CurrentActor(userId, Set.of(AppRole.USER), false);
    }
}
```

`src/test/java/com/plantarena/tournaments/application/AdvanceGlobalCompetitionServiceTest.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.tournaments.application.port.out.ClusteringGateway.AssignedCluster;
import com.plantarena.tournaments.application.port.out.ClusteringGateway.MemberLocation;
import com.plantarena.tournaments.application.port.out.ParticipantLocationsGateway.UserLocation;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.application.port.out.PlantLifecycleGateway;
import com.plantarena.tournaments.application.support.FakeClusteringGateway;
import com.plantarena.tournaments.application.support.FakeParticipantLocationsGateway;
import com.plantarena.tournaments.application.support.FakePlantEligibilityGateway;
import com.plantarena.tournaments.application.support.FakePlantLifecycleGateway;
import com.plantarena.tournaments.application.support.InMemoryQualificationEpochRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryVotingWindowRepository;
import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.EpochStatus;
import com.plantarena.tournaments.domain.QualificationEpoch;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.WindowScope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Продвижение границ (раздел 8, алгоритмы 2–8): фиксированный порядок
 * одновременных границ и идемпотентность повтора.
 */
@DisplayName("AdvanceGlobalCompetitionService: порядок границ и идемпотентность")
class AdvanceGlobalCompetitionServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-27T10:00:00Z");
    private static final Instant T1 = T0.plus(Duration.ofHours(25)); // все дедлайны прошли

    private final InMemoryVotingWindowRepository windows = new InMemoryVotingWindowRepository();
    private final InMemoryQualificationEpochRepository epochs =
        new InMemoryQualificationEpochRepository();
    private final InMemoryTournamentEntryRepository entries = new InMemoryTournamentEntryRepository();
    private final FakeParticipantLocationsGateway locations = new FakeParticipantLocationsGateway();
    private final FakeClusteringGateway clustering = new FakeClusteringGateway();
    private final FakePlantEligibilityGateway eligibility = new FakePlantEligibilityGateway();
    private final FakePlantLifecycleGateway plantLifecycle = new FakePlantLifecycleGateway();

    private final AdvanceGlobalCompetitionService service = new AdvanceGlobalCompetitionService(
        windows, epochs, entries, eligibility, plantLifecycle, locations, clustering,
        new GlobalCompetitionSettings(Duration.ofHours(24), Duration.ofHours(6)),
        new org.springframework.transaction.support.TransactionTemplate());

    @Test
    @DisplayName("одновременные границы: квалификация закрыта → финал закрыт → новый финал с продвинутым → новая эпоха")
    void порядок_одновременных_границ() {
        // эпоха 1: кластер с двумя участками (u1 победит), дедлайн прошёл
        UUID epochId = UUID.randomUUID();
        UUID clusterId = UUID.randomUUID();
        UUID loser = globalEntry(EntryStatus.QUALIFYING);
        UUID winner = globalEntry(EntryStatus.QUALIFYING);
        epochs.save(QualificationEpoch.open(epochId, GlobalCompetitionId.VALUE, 1,
            T0.minus(Duration.ofHours(25)), T0.minus(Duration.ofHours(1)), T0));
        windows.save(VotingWindow.openQualification(GlobalCompetitionId.VALUE, epochId,
            clusterId, "u4pu", 1,
            List.of(seed(winner), seed(loser)),
            T0.minus(Duration.ofHours(25)), T0.minus(Duration.ofHours(1)), T0));
        windows.save(VotingWindow.openQualification(GlobalCompetitionId.VALUE, epochId,
            UUID.randomUUID(), "u8t", 1, List.of(seed(globalEntry(EntryStatus.QUALIFYING))),
            T0.minus(Duration.ofHours(25)), T0.minus(Duration.ofHours(1)), T0));
        // финал 1: один выживший (n=1 — лидер), дедлайн прошёл
        UUID survivor = globalEntry(EntryStatus.FINALIST);
        windows.save(VotingWindow.openFinal(GlobalCompetitionId.VALUE, 1,
            List.of(seed(survivor)),
            T0.minus(Duration.ofHours(7)), T0.minus(Duration.ofHours(1)), T0));
        // очередь: новая заявка с координатами
        UUID queued = globalEntry(EntryStatus.QUEUED);
        TournamentEntry queuedEntry = entries.findById(queued).orElseThrow();
        locations.locations.put(queuedEntry.userId(),
            new UserLocation(55.7558, 37.6173, 1L));

        AdvanceGlobalCompetitionUseCase.AdvanceReport report = service.advance(T1);

        // (1) квалификация закрыта: top-1 (первый по joinedAt/id при равных счётах) продвинут
        assertThat(report.qualificationClosed()).isEqualTo(2);
        assertThat(entries.findById(winner).orElseThrow().status())
            .isEqualTo(EntryStatus.FINAL_PENDING);
        assertThat(entries.findById(loser).orElseThrow().status())
            .isEqualTo(EntryStatus.ELIMINATED);
        assertThat(epochs.findById(epochId).orElseThrow().status())
            .isEqualTo(EpochStatus.CLOSED);
        // гибель + COOLDOWN 24 ч + освобождение резерва (алгоритм 3, допущение 2)
        assertThat(plantLifecycle.deaths).anySatisfy(death -> {
            assertThat(death.kind()).isEqualTo(PlantLifecycleGateway.RestrictionKind.COOLDOWN);
            assertThat(death.expiresAt()).isEqualTo(T1.plus(Duration.ofHours(24)));
        });
        assertThat(eligibility.releasedIds)
            .contains(entries.findById(loser).orElseThrow().reservationId());
        // (2) старый финал закрыт: лидер остался FINALIST (алгоритм 7)
        assertThat(report.finalClosed()).isEqualTo(1);
        assertThat(entries.findById(survivor).orElseThrow().status())
            .isEqualTo(EntryStatus.FINALIST);
        // (3) новый финал: выживший + продвинутый (алгоритм 6)
        assertThat(report.finalsOpened()).isEqualTo(1);
        assertThat(entries.findById(winner).orElseThrow().status())
            .isEqualTo(EntryStatus.FINALIST);
        assertThat(windows.findOpenByScope(GlobalCompetitionId.VALUE, WindowScope.FINAL))
            .map(VotingWindow::sequence).contains(2);
        // (4) новая эпоха: заявка из очереди кластеризована (алгоритм 2)
        assertThat(report.epochsOpened()).isEqualTo(1);
        assertThat(clustering.called).isTrue();
        assertThat(entries.findById(queued).orElseThrow().status())
            .isEqualTo(EntryStatus.QUALIFYING);
        assertThat(epochs.findOpenByTournamentId(GlobalCompetitionId.VALUE)).isPresent();
    }

    @Test
    @DisplayName("повторный advance — no-op: ничего не закрывается и не открывается повторно")
    void идемпотентность_повтора() {
        UUID epochId = UUID.randomUUID();
        UUID entryId = globalEntry(EntryStatus.QUALIFYING);
        epochs.save(QualificationEpoch.open(epochId, GlobalCompetitionId.VALUE, 1,
            T0.minus(Duration.ofHours(25)), T0.minus(Duration.ofHours(1)), T0));
        windows.save(VotingWindow.openQualification(GlobalCompetitionId.VALUE, epochId,
            UUID.randomUUID(), "u4pu", 1, List.of(seed(entryId)),
            T0.minus(Duration.ofHours(25)), T0.minus(Duration.ofHours(1)), T0));
        service.advance(T1);

        AdvanceGlobalCompetitionUseCase.AdvanceReport second = service.advance(T1);

        assertThat(second.qualificationClosed()).isZero();
        assertThat(second.finalClosed()).isZero();
        assertThat(second.finalsOpened()).isZero(); // финалист один — окно уже открыто? нет:
        // после первого advance финал с 1 участком открыт — повтор не создаёт второй
        assertThat(windows.findOpenByScope(GlobalCompetitionId.VALUE, WindowScope.FINAL))
            .map(VotingWindow::sequence).contains(1);
        assertThat(second.epochsOpened()).isZero(); // очередь пуста
        assertThat(plantLifecycle.deaths).isEmpty(); // никто не погиб повторно
    }

    @Test
    @DisplayName("эпоха не открывается без QUEUED-заявок (пустые эпохи не создаются)")
    void без_очереди_эпоха_не_открывается() {
        AdvanceGlobalCompetitionUseCase.AdvanceReport report = service.advance(T1);
        assertThat(report.epochsOpened()).isZero();
        assertThat(clustering.called).isFalse();
    }

    private UUID globalEntry(EntryStatus status) {
        TournamentEntry entry = TournamentEntry.queueForGlobal(GlobalCompetitionId.VALUE,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), T0);
        while (entry.status() != status) {
            if (entry.status() == EntryStatus.QUEUED) {
                entry.startQualifying();
            } else if (entry.status() == EntryStatus.QUALIFYING) {
                entry.promoteToFinalPending();
            } else {
                entry.becomeFinalist();
            }
        }
        entries.save(entry);
        return entry.id();
    }

    private VotingWindow.ParticipantSeed seed(UUID entryId) {
        TournamentEntry entry = entries.findById(entryId).orElseThrow();
        return new VotingWindow.ParticipantSeed(entry.id(), entry.userId(), entry.joinedAt());
    }
}
```

(поля `deaths`/`reservedIds`/`releasedIds`/`lastIdempotencyKey` у фейков — по их фактическому устройству; если имена отличаются, использовать фактические; при необходимости дополнить фейк записью `death` с kind/expiresAt.)

`src/test/java/com/plantarena/tournaments/application/GlobalQueryServiceTest.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.web.PaginationParams;
import com.plantarena.tournaments.application.port.in.GetGlobalLeaderboardUseCase.GlobalLeaderboard;
import com.plantarena.tournaments.application.port.in.ListGlobalClustersUseCase.GlobalCluster;
import com.plantarena.tournaments.application.support.InMemoryQualificationEpochRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryVotingWindowRepository;
import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.QualificationEpoch;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingSubject;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.WindowScope;
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

/** Запросы глобального турнира (раздел 8, алгоритм 9; раздел 13). */
@DisplayName("GlobalQueryService: кластеры, лидерборды, моё участие")
class GlobalQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final InMemoryVotingWindowRepository windows = new InMemoryVotingWindowRepository();
    private final InMemoryQualificationEpochRepository epochs =
        new InMemoryQualificationEpochRepository();
    private final InMemoryTournamentEntryRepository entries = new InMemoryTournamentEntryRepository();
    private final GlobalQueryService service = new GlobalQueryService(windows, epochs, entries,
        new GlobalCompetitionSettings(Duration.ofHours(24), Duration.ofHours(6)),
        Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("кластеры текущей эпохи: clusterId/ключ/окно/состав, X-Total-Count")
    void кластеры_эпохи() {
        UUID epochId = UUID.randomUUID();
        UUID clusterId = UUID.randomUUID();
        epochs.save(QualificationEpoch.open(epochId, GlobalCompetitionId.VALUE, 1,
            NOW, NOW.plus(Duration.ofHours(24)), NOW));
        windows.save(VotingWindow.openQualification(GlobalCompetitionId.VALUE, epochId,
            clusterId, "u4pu", 1, List.of(seed(), seed()), NOW,
            NOW.plus(Duration.ofHours(24)), NOW));

        ListGlobalClustersUseCase.ClusterListResult result =
            service.list(PaginationParams.of(0, 20));

        assertThat(result.total()).isEqualTo(1);
        GlobalCluster cluster = result.items().get(0);
        assertThat(cluster.clusterId()).isEqualTo(clusterId);
        assertThat(cluster.clusterKey()).isEqualTo("u4pu");
        assertThat(cluster.memberCount()).isEqualTo(2);
        assertThat(cluster.closesAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("финальный лидерборд: scope/windowId/closesAt/asOf + ранжирование")
    void финальный_лидерборд() {
        VotingWindow.ParticipantSeed first = seed();
        VotingWindow.ParticipantSeed second = seed();
        VotingWindow window = VotingWindow.openFinal(GlobalCompetitionId.VALUE, 1,
            List.of(first, second), NOW.minus(Duration.ofHours(1)),
            NOW.plus(Duration.ofHours(5)), NOW);
        window.castVote(VotingSubject.user(UUID.randomUUID()), first.entryId(),
            VoteValue.LIKE, NOW);
        windows.save(window);

        GlobalLeaderboard leaderboard = service.finalLeaderboard(PaginationParams.of(0, 20));

        assertThat(leaderboard.scope()).isEqualTo("FINAL");
        assertThat(leaderboard.windowId()).isEqualTo(window.id());
        assertThat(leaderboard.closesAt()).isEqualTo(window.closesAt());
        assertThat(leaderboard.asOf()).isEqualTo(NOW);
        assertThat(leaderboard.items()).extracting(GetGlobalLeaderboardUseCase.Item::entryId)
            .containsExactly(first.entryId(), second.entryId());
        assertThat(leaderboard.items().get(0).score()).isEqualTo(1L);
    }

    @Test
    @DisplayName("финала ещё не было — пустой лидерборд без windowId (алгоритм 7: n=0 ждёт)")
    void финала_нет() {
        GlobalLeaderboard leaderboard = service.finalLeaderboard(PaginationParams.of(0, 20));
        assertThat(leaderboard.windowId()).isNull();
        assertThat(leaderboard.items()).isEmpty();
    }

    @Test
    @DisplayName("лидерборд кластера: по clusterId; неизвестный — 404")
    void лидерборд_кластера() {
        UUID clusterId = UUID.randomUUID();
        windows.save(VotingWindow.openQualification(GlobalCompetitionId.VALUE,
            UUID.randomUUID(), clusterId, "u4pu", 1, List.of(seed()), NOW,
            NOW.plus(Duration.ofHours(24)), NOW));

        GlobalLeaderboard leaderboard = service.clusterLeaderboard(clusterId,
            PaginationParams.of(0, 20));
        assertThat(leaderboard.scope()).isEqualTo("QUALIFICATION");
        assertThat(leaderboard.items()).hasSize(1);

        assertThatThrownBy(() -> service.clusterLeaderboard(UUID.randomUUID(),
            PaginationParams.of(0, 20)))
            .isInstanceOf(ClusterNotFoundException.class);
    }

    @Test
    @DisplayName("моё активное участие: QUEUED виден, WITHDRAWN нет")
    void моё_участие() {
        UUID userId = UUID.randomUUID();
        TournamentEntry active = TournamentEntry.queueForGlobal(GlobalCompetitionId.VALUE,
            userId, UUID.randomUUID(), UUID.randomUUID(), NOW);
        TournamentEntry withdrawn = TournamentEntry.queueForGlobal(GlobalCompetitionId.VALUE,
            userId, UUID.randomUUID(), UUID.randomUUID(), NOW);
        withdrawn.withdraw();
        entries.save(active);
        entries.save(withdrawn);

        assertThat(service.findActive(actor(userId)))
            .map(SubmitGlobalEntryUseCase.GlobalEntryView::id).contains(active.id());
    }

    private VotingWindow.ParticipantSeed seed() {
        TournamentEntry entry = TournamentEntry.queueForGlobal(GlobalCompetitionId.VALUE,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW);
        entries.save(entry);
        return new VotingWindow.ParticipantSeed(entry.id(), entry.userId(), entry.joinedAt());
    }

    private CurrentActor actor(UUID userId) {
        return new CurrentActor(userId, Set.of(AppRole.USER), false);
    }
}
```

- [ ] **Step 5: Запустить — красный**

```bash
./mvnw -q test -Dtest='SubmitGlobalEntryServiceTest,WithdrawGlobalEntryServiceTest,AdvanceGlobalCompetitionServiceTest,GlobalQueryServiceTest,InMemoryQualificationEpochRepositoryContractTest'
```

Ожидание: FAIL (компиляция): сервисы не существуют.

- [ ] **Step 6: Реализация сервисов и out-адаптеров**

`src/main/java/com/plantarena/tournaments/application/SubmitGlobalEntryService.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.SubmitGlobalEntryUseCase;
import com.plantarena.tournaments.application.port.out.ParticipantLocationsGateway;
import com.plantarena.tournaments.application.port.out.PlantDirectoryGateway;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Подача заявки в глобальный турнир (раздел 8, алгоритм 1): только APPROVED
 * (раздел 6), координаты обязательны (раздел 8), одно активное участие
 * (допущение 5). Резерв изображения — с idempotency key = entryId, в одной
 * tx с созданием участия (по образцу ADR-010).
 */
@Service
public class SubmitGlobalEntryService implements SubmitGlobalEntryUseCase {

    private final PlantDirectoryGateway plants;
    private final ParticipantLocationsGateway locations;
    private final PlantEligibilityGateway eligibility;
    private final TournamentEntryRepository entries;
    private final TournamentsAccessPolicy accessPolicy;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public SubmitGlobalEntryService(PlantDirectoryGateway plants,
                                    ParticipantLocationsGateway locations,
                                    PlantEligibilityGateway eligibility,
                                    TournamentEntryRepository entries,
                                    TournamentsAccessPolicy accessPolicy, Clock clock,
                                    TransactionTemplate transactionTemplate) {
        this.plants = plants;
        this.locations = locations;
        this.eligibility = eligibility;
        this.entries = entries;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public GlobalEntryView submit(CurrentActor actor, UUID plantId) {
        accessPolicy.requireIdentified(actor);
        PlantDirectoryGateway.PlantSnapshot plant = plants.findById(plantId)
            .filter(snapshot -> snapshot.ownerId().equals(actor.userId()))
            .orElseThrow(() -> new SubmittedPlantNotFoundException(
                "Растение не найдено: " + plantId));
        if (!plant.approved()) {
            throw new PlantNotApprovedException(
                "Глобальный турнир принимает только одобренные модерацией растения (раздел 6)");
        }
        locations.findLocation(actor.userId())
            .orElseThrow(() -> new LocationRequiredException(
                "Для глобального участия нужны координаты в профиле (PUT /me/location)"));
        if (entries.findActiveGlobalByUserId(GlobalCompetitionId.VALUE, actor.userId())
            .isPresent()) {
            throw new ActiveGlobalEntryExistsException(
                "У пользователя уже есть активное глобальное участие (допущение 5)");
        }
        return transactionTemplate.execute(status -> {
            UUID entryId = UUID.randomUUID();
            UUID reservationId = eligibility.reserveSubmission(actor.userId(), plantId, entryId);
            TournamentEntry entry = TournamentEntry.queueForGlobal(GlobalCompetitionId.VALUE,
                actor.userId(), plantId, reservationId, clock.instant());
            entries.save(entry);
            return new GlobalEntryView(entry.id(), entry.plantId(), entry.status().name(),
                entry.joinedAt());
        });
    }
}
```

`src/main/java/com/plantarena/tournaments/application/WithdrawGlobalEntryService.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.WithdrawGlobalEntryUseCase;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Снятие заявки из очереди (раздел 13): только владелец (чужая скрыта — 404)
 * и только QUEUED (включённое в окно — 409 ENTRY_IN_WINDOW); резерв
 * освобождается в той же tx.
 */
@Service
public class WithdrawGlobalEntryService implements WithdrawGlobalEntryUseCase {

    private final TournamentEntryRepository entries;
    private final PlantEligibilityGateway eligibility;
    private final TournamentsAccessPolicy accessPolicy;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public WithdrawGlobalEntryService(TournamentEntryRepository entries,
                                      PlantEligibilityGateway eligibility,
                                      TournamentsAccessPolicy accessPolicy, Clock clock,
                                      TransactionTemplate transactionTemplate) {
        this.entries = entries;
        this.eligibility = eligibility;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void withdraw(CurrentActor actor, UUID entryId) {
        accessPolicy.requireIdentified(actor);
        transactionTemplate.executeWithoutResult(status -> {
            TournamentEntry entry = entries.findById(entryId)
                .filter(found -> found.userId().equals(actor.userId()))
                .orElseThrow(() -> new GlobalEntryNotFoundException(
                    "Участие не найдено: " + entryId));
            if (entry.status() != EntryStatus.QUEUED) {
                throw new GlobalEntryNotWithdrawableException(
                    "Снять можно только заявку из очереди (QUEUED), текущий статус: "
                        + entry.status());
            }
            entry.withdraw();
            entries.save(entry);
            eligibility.release(entry.reservationId());
        });
    }
}
```

`src/main/java/com/plantarena/tournaments/application/AdvanceGlobalCompetitionService.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.event.IntegrationEventPublisher;
import com.plantarena.tournaments.api.event.EntryEliminatedEvent;
import com.plantarena.tournaments.application.port.in.AdvanceGlobalCompetitionUseCase;
import com.plantarena.tournaments.application.port.out.ClusteringGateway;
import com.plantarena.tournaments.application.port.out.ParticipantLocationsGateway;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.application.port.out.PlantLifecycleGateway;
import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.QualificationEpoch;
import com.plantarena.tournaments.domain.QualificationEpochRepository;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import com.plantarena.tournaments.domain.WindowScope;
import com.plantarena.tournaments.domain.WindowStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Продвижение границ глобального турнира (раздел 8, алгоритм 6) — один use
 * case для scheduler'а и demo-ручки. Фиксированный порядок: (1) закрыть
 * due-квалификацию, (2) закрыть due-финал, (3) открыть следующий финал,
 * (4) открыть следующую эпоху. Каждое окно/шаг — короткая tx (ADR-012);
 * идемпотентность по статусам: CLOSED-окно и существующие OPEN-эпоха/финал —
 * no-op, рестарт не убивает растения повторно (раздел 12.3).
 */
@Service
public class AdvanceGlobalCompetitionService implements AdvanceGlobalCompetitionUseCase {

    private static final Logger log =
        LoggerFactory.getLogger(AdvanceGlobalCompetitionService.class);
    private static final int BATCH_SIZE = 50;
    private static final Duration GLOBAL_COOLDOWN = Duration.ofHours(24);

    private final VotingWindowRepository windows;
    private final QualificationEpochRepository epochs;
    private final TournamentEntryRepository entries;
    private final PlantEligibilityGateway eligibility;
    private final PlantLifecycleGateway plantLifecycle;
    private final ParticipantLocationsGateway locations;
    private final ClusteringGateway clustering;
    private final GlobalCompetitionSettings settings;
    private final TransactionTemplate transactionTemplate;

    public AdvanceGlobalCompetitionService(VotingWindowRepository windows,
                                           QualificationEpochRepository epochs,
                                           TournamentEntryRepository entries,
                                           PlantEligibilityGateway eligibility,
                                           PlantLifecycleGateway plantLifecycle,
                                           ParticipantLocationsGateway locations,
                                           ClusteringGateway clustering,
                                           GlobalCompetitionSettings settings,
                                           TransactionTemplate transactionTemplate) {
        this.windows = windows;
        this.epochs = epochs;
        this.entries = entries;
        this.eligibility = eligibility;
        this.plantLifecycle = plantLifecycle;
        this.locations = locations;
        this.clustering = clustering;
        this.settings = settings;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public AdvanceReport advance(Instant now) {
        int qualificationClosed = closeDueQualification(now);
        int finalClosed = closeDueFinal(now);
        int finalsOpened = openNextFinal(now);
        int epochsOpened = openNextEpoch(now);
        return new AdvanceReport(qualificationClosed, finalClosed, finalsOpened, epochsOpened);
    }

    /** (1) Закрытие due-квалификационных окон (алгоритмы 3–5). */
    private int closeDueQualification(Instant now) {
        int closed = 0;
        for (UUID windowId
            : windows.findDueForCloseByScope(WindowScope.QUALIFICATION, now, BATCH_SIZE)) {
            try {
                transactionTemplate.executeWithoutResult(status ->
                    closeQualificationOne(windowId, now));
                closed++;
            } catch (RuntimeException e) {
                log.warn("Закрытие квалификации {} не удалось, будет повторено: {}",
                    windowId, e.getMessage());
            }
        }
        return closed;
    }

    private void closeQualificationOne(UUID windowId, Instant now) {
        VotingWindow window = windows.findByIdForUpdate(windowId)
            .orElseThrow(() -> new IllegalStateException("Окно не найдено: " + windowId));
        if (window.status() != WindowStatus.OPEN || now.isBefore(window.closesAt())) {
            return; // уже закрыто (идемпотентность) или ещё не due
        }
        VotingWindow.QualificationCloseOutcome outcome = window.closeQualification(now);
        windows.save(window);

        TournamentEntry promoted = findEntry(outcome.promotedEntryId());
        promoted.promoteToFinalPending();
        entries.save(promoted);

        for (TournamentEntry entry : eliminatedSorted(outcome.eliminatedEntryIds())) {
            entry.eliminateFromGlobal();
            entries.save(entry);
            registerGlobalDeath(entry, "Поражение в квалификации эпохи " + window.sequence(),
                now);
            publishEliminated(window, entry, now);
        }
        if (windows.countOpenByEpochId(window.epochId()) == 0) {
            QualificationEpoch epoch = epochs.findById(window.epochId())
                .orElseThrow(() -> new IllegalStateException(
                    "Эпоха не найдена: " + window.epochId()));
            epoch.close(now);
            epochs.save(epoch);
        }
    }

    /** (2) Закрытие due-финального окна (алгоритмы 7–8). */
    private int closeDueFinal(Instant now) {
        int closed = 0;
        for (UUID windowId : windows.findDueForCloseByScope(WindowScope.FINAL, now, BATCH_SIZE)) {
            try {
                transactionTemplate.executeWithoutResult(status -> closeFinalOne(windowId, now));
                closed++;
            } catch (RuntimeException e) {
                log.warn("Закрытие финала {} не удалось, будет повторено: {}",
                    windowId, e.getMessage());
            }
        }
        return closed;
    }

    private void closeFinalOne(UUID windowId, Instant now) {
        VotingWindow window = windows.findByIdForUpdate(windowId)
            .orElseThrow(() -> new IllegalStateException("Окно не найдено: " + windowId));
        if (window.status() != WindowStatus.OPEN || now.isBefore(window.closesAt())) {
            return;
        }
        VotingWindow.FinalCloseOutcome outcome = window.closeFinal(now);
        windows.save(window);
        for (TournamentEntry entry : eliminatedSorted(outcome.eliminatedEntryIds())) {
            entry.eliminateFromGlobal();
            entries.save(entry);
            registerGlobalDeath(entry, "Поражение в финальном окне " + window.sequence(), now);
            publishEliminated(window, entry, now);
        }
        // выжившие остаются FINALIST — переход в следующее окно (3)
    }

    /** (3) Следующее финальное окно: выжившие + все FINAL_PENDING (алгоритм 6). */
    private int openNextFinal(Instant now) {
        Boolean opened = transactionTemplate.execute(status -> {
            if (windows.findOpenByScope(GlobalCompetitionId.VALUE, WindowScope.FINAL)
                .isPresent()) {
                return false;
            }
            for (TournamentEntry pending
                : entries.findByTournamentIdAndStatus(GlobalCompetitionId.VALUE,
                    EntryStatus.FINAL_PENDING)) {
                pending.becomeFinalist();
                entries.save(pending);
            }
            List<TournamentEntry> finalists = entries.findByTournamentIdAndStatus(
                GlobalCompetitionId.VALUE, EntryStatus.FINALIST);
            if (finalists.isEmpty()) {
                return false; // n = 0: финал ждёт заявок (алгоритм 7)
            }
            int nextSequence = windows
                .findLatestByTournamentIdAndScope(GlobalCompetitionId.VALUE, WindowScope.FINAL)
                .map(VotingWindow::sequence).orElse(0) + 1;
            windows.save(VotingWindow.openFinal(GlobalCompetitionId.VALUE, nextSequence,
                seeds(finalists), now, now.plus(settings.finalWindowDuration()), now));
            return true;
        });
        return Boolean.TRUE.equals(opened) ? 1 : 0;
    }

    /** (4) Следующая эпоха: QUEUED → кластеризация → QUALIFYING + окна (алгоритм 2). */
    private int openNextEpoch(Instant now) {
        Boolean opened = transactionTemplate.execute(status -> {
            if (epochs.findOpenByTournamentId(GlobalCompetitionId.VALUE).isPresent()) {
                return false;
            }
            List<TournamentEntry> queued = entries.findByTournamentIdAndStatus(
                GlobalCompetitionId.VALUE, EntryStatus.QUEUED);
            if (queued.isEmpty()) {
                return false; // пустые эпохи не создаются (дизайн, решение 4)
            }
            List<ClusteringGateway.MemberLocation> members = new ArrayList<>();
            for (TournamentEntry entry : queued) {
                locations.findLocation(entry.userId()).ifPresent(location ->
                    members.add(new ClusteringGateway.MemberLocation(entry.id(),
                        entry.userId(), location.latitude(), location.longitude(),
                        location.locationVersion())));
            }
            if (members.isEmpty()) {
                return false;
            }
            UUID epochId = UUID.randomUUID();
            List<ClusteringGateway.AssignedCluster> clusters =
                clustering.assignClusters(epochId, members);
            int nextSequence = epochs.findLastByTournamentId(GlobalCompetitionId.VALUE)
                .map(QualificationEpoch::sequence).orElse(0) + 1;
            Instant closesAt = now.plus(settings.epochDuration());
            epochs.save(QualificationEpoch.open(epochId, GlobalCompetitionId.VALUE,
                nextSequence, now, closesAt, now));
            for (ClusteringGateway.AssignedCluster cluster : clusters) {
                List<TournamentEntry> clusterEntries = cluster.entryIds().stream()
                    .map(this::findEntry).toList();
                for (TournamentEntry entry : clusterEntries) {
                    entry.startQualifying();
                    entries.save(entry);
                }
                windows.save(VotingWindow.openQualification(GlobalCompetitionId.VALUE, epochId,
                    cluster.clusterId(), cluster.clusterKey(), nextSequence,
                    seeds(clusterEntries), now, closesAt, now));
            }
            return true;
        });
        return Boolean.TRUE.equals(opened) ? 1 : 0;
    }

    /** Гибель + суточный запрет совпавшей картинки + освобождение резерва. */
    private void registerGlobalDeath(TournamentEntry entry, String reason, Instant now) {
        plantLifecycle.registerDeath(entry.plantId(),
            PlantLifecycleGateway.RestrictionKind.COOLDOWN, now.plus(GLOBAL_COOLDOWN),
            reason, entry.id());
        eligibility.release(entry.reservationId());
    }

    private void publishEliminated(VotingWindow window, TournamentEntry entry, Instant now) {
        UUID eventId = UUID.randomUUID();
        IntegrationEventPublisher publisher = this.eventPublisher;
        publisher.publish(new EntryEliminatedEvent(eventId, EntryEliminatedEvent.TYPE,
            EntryEliminatedEvent.SCHEMA_VERSION, window.id(), window.version(), now, eventId,
            new EntryEliminatedEvent.Payload(window.tournamentId(), entry.id(), entry.userId(),
                entry.plantId(), window.sequence())));
    }

    private List<TournamentEntry> eliminatedSorted(List<UUID> entryIds) {
        return entryIds.stream().map(this::findEntry)
            .sorted(Comparator.comparing(TournamentEntry::plantId)) // устойчивый порядок (12.3)
            .toList();
    }

    private List<VotingWindow.ParticipantSeed> seeds(List<TournamentEntry> finalists) {
        return finalists.stream()
            .map(entry -> new VotingWindow.ParticipantSeed(entry.id(), entry.userId(),
                entry.joinedAt()))
            .toList();
    }

    private TournamentEntry findEntry(UUID entryId) {
        return entries.findById(entryId)
            .orElseThrow(() -> new IllegalStateException("Участие не найдено: " + entryId));
    }
}
```

Внимание: в конструктор и поле добавить `IntegrationEventPublisher eventPublisher` (в коде выше `publishEliminated` ссылается на `this.eventPublisher` — объявить поле и параметр конструктора по образцу `CloseVotingWindowService`; в тестах передать `new FakeEventPublisher()`).

`src/main/java/com/plantarena/tournaments/application/GlobalQueryService.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.web.PaginationParams;
import com.plantarena.tournaments.application.port.in.GetGlobalInfoUseCase;
import com.plantarena.tournaments.application.port.in.GetGlobalLeaderboardUseCase;
import com.plantarena.tournaments.application.port.in.GetMyGlobalEntryUseCase;
import com.plantarena.tournaments.application.port.in.ListGlobalClustersUseCase;
import com.plantarena.tournaments.application.port.in.SubmitGlobalEntryUseCase.GlobalEntryView;
import com.plantarena.tournaments.domain.ParticipantRanking;
import com.plantarena.tournaments.domain.QualificationEpoch;
import com.plantarena.tournaments.domain.QualificationEpochRepository;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import com.plantarena.tournaments.domain.WindowParticipant;
import com.plantarena.tournaments.domain.WindowScope;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Запросы глобального турнира (раздел 8, алгоритм 9; раздел 13): публичные
 * конфигурация/кластеры/лидерборды (scope + windowId + closesAt + asOf) и
 * своё активное участие. Очки разных окон и кластеров не смешиваются.
 */
@Service
@Transactional(readOnly = true)
public class GlobalQueryService implements GetGlobalInfoUseCase, ListGlobalClustersUseCase,
        GetGlobalLeaderboardUseCase, GetMyGlobalEntryUseCase {

    private final VotingWindowRepository windows;
    private final QualificationEpochRepository epochs;
    private final TournamentEntryRepository entries;
    private final GlobalCompetitionSettings settings;
    private final TournamentsAccessPolicy accessPolicy;
    private final Clock clock;

    public GlobalQueryService(VotingWindowRepository windows,
                              QualificationEpochRepository epochs,
                              TournamentEntryRepository entries,
                              GlobalCompetitionSettings settings,
                              TournamentsAccessPolicy accessPolicy, Clock clock) {
        this.windows = windows;
        this.epochs = epochs;
        this.entries = entries;
        this.settings = settings;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    @Override
    public GlobalInfo globalInfo() {
        EpochInfo epoch = epochs.findOpenByTournamentId(GlobalCompetitionId.VALUE)
            .map(found -> new EpochInfo(found.id(), found.sequence(), found.opensAt(),
                found.closesAt()))
            .orElse(null);
        WindowInfo finalWindow = currentFinal()
            .map(window -> new WindowInfo(window.id(), window.sequence(), window.opensAt(),
                window.closesAt()))
            .orElse(null);
        return new GlobalInfo(settings.epochDuration().toSeconds(),
            settings.finalWindowDuration().toSeconds(), epoch, finalWindow);
    }

    @Override
    public ClusterListResult list(PaginationParams page) {
        List<GlobalCluster> clusters = epochs
            .findOpenByTournamentId(GlobalCompetitionId.VALUE)
            .stream()
            .flatMap(epoch -> windows.findOpenByEpochId(epoch.id()).stream())
            .map(window -> new GlobalCluster(window.clusterId(), window.clusterKey(),
                window.id(), window.participants().size(), window.closesAt()))
            .sorted(Comparator.comparing(GlobalCluster::clusterKey)
                .thenComparing(GlobalCluster::clusterId))
            .toList();
        List<GlobalCluster> pageItems = clusters.stream()
            .skip(page.offset()).limit(page.size()).toList();
        return new ClusterListResult(pageItems, clusters.size());
    }

    @Override
    public GlobalLeaderboard finalLeaderboard(PaginationParams page) {
        Optional<VotingWindow> window = currentFinal();
        if (window.isEmpty()) {
            return new GlobalLeaderboard("FINAL", null, null, clock.instant(), List.of());
        }
        return leaderboard("FINAL", window.orElseThrow(), page);
    }

    @Override
    public GlobalLeaderboard clusterLeaderboard(UUID clusterId, PaginationParams page) {
        VotingWindow window = windows.findOpenByClusterId(clusterId)
            .orElseThrow(() -> new ClusterNotFoundException("Кластер не найден: " + clusterId));
        return leaderboard("QUALIFICATION", window, page);
    }

    @Override
    public Optional<GlobalEntryView> findActive(CurrentActor actor) {
        accessPolicy.requireIdentified(actor);
        return entries.findActiveGlobalByUserId(GlobalCompetitionId.VALUE, actor.userId())
            .map(entry -> new GlobalEntryView(entry.id(), entry.plantId(),
                entry.status().name(), entry.joinedAt()));
    }

    private GlobalLeaderboard leaderboard(String scope, VotingWindow window,
                                           PaginationParams page) {
        List<WindowParticipant> ranked = ParticipantRanking.rank(window.participants());
        List<Item> items = ranked.stream()
            .skip(page.offset()).limit(page.size())
            .map(participant -> new Item(participant.entryId(), participant.userId(),
                participant.score()))
            .toList();
        return new GlobalLeaderboard(scope, window.id(), window.closesAt(), clock.instant(),
            items);
    }

    private Optional<VotingWindow> currentFinal() {
        return windows.findOpenByScope(GlobalCompetitionId.VALUE, WindowScope.FINAL)
            .or(() -> windows.findLatestByTournamentIdAndScope(GlobalCompetitionId.VALUE,
                WindowScope.FINAL));
    }
}
```

`src/main/java/com/plantarena/tournaments/application/EnsureGlobalCompetitionService.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.tournaments.application.port.in.EnsureGlobalCompetitionUseCase;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Идемпотентное создание единственной записи глобального турнира (раздел 8,
 * ADR-012): фиксированный id, статус RUNNING навсегда, creator — системный
 * UUID. Вызывается bootstrap-раннером при старте; повтор — no-op.
 */
@Service
public class EnsureGlobalCompetitionService implements EnsureGlobalCompetitionUseCase {

    private final TournamentRepository tournaments;
    private final Clock clock;

    public EnsureGlobalCompetitionService(TournamentRepository tournaments, Clock clock) {
        this.tournaments = tournaments;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void ensure() {
        if (tournaments.findById(GlobalCompetitionId.VALUE).isPresent()) {
            return;
        }
        tournaments.save(Tournament.global(GlobalCompetitionId.VALUE, GlobalCompetitionId.VALUE,
            clock.instant()));
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/identity/InProcessParticipantLocations.java`:

```java
package com.plantarena.tournaments.adapter.out.identity;

import com.plantarena.identity.api.UserDirectory;
import com.plantarena.tournaments.application.port.out.ParticipantLocationsGateway;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL-адаптер identity → tournaments (раздел 4.3): координаты профиля через
 * OHS-контракт identity.api.UserDirectory. В лабе №2 меняется на HTTP-клиент.
 */
@Component
public class InProcessParticipantLocations implements ParticipantLocationsGateway {

    private final UserDirectory userDirectory;

    public InProcessParticipantLocations(UserDirectory userDirectory) {
        this.userDirectory = userDirectory;
    }

    @Override
    public Optional<UserLocation> findLocation(UUID userId) {
        return userDirectory.findLocation(userId)
            .map(location -> new UserLocation(location.latitude(), location.longitude(),
                location.locationVersion()));
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/geo/InProcessClusteringGateway.java`:

```java
package com.plantarena.tournaments.adapter.out.geo;

import com.plantarena.geo.api.ClusterAssignment;
import com.plantarena.tournaments.application.port.out.ClusteringGateway;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL-адаптер geo → tournaments (раздел 4.3): команда кластеризации через
 * контракт geo.api.ClusterAssignment; geo получает координаты во входной
 * команде. В лабе №2 меняется на HTTP-клиент.
 */
@Component
public class InProcessClusteringGateway implements ClusteringGateway {

    private final ClusterAssignment clusterAssignment;

    public InProcessClusteringGateway(ClusterAssignment clusterAssignment) {
        this.clusterAssignment = clusterAssignment;
    }

    @Override
    public List<AssignedCluster> assignClusters(UUID epochId, List<MemberLocation> members) {
        List<ClusterAssignment.Member> apiMembers = members.stream()
            .map(member -> new ClusterAssignment.Member(member.entryId(), member.userId(),
                member.latitude(), member.longitude(), member.locationVersion()))
            .toList();
        return clusterAssignment.assignClusters(epochId, apiMembers).stream()
            .map(cluster -> new AssignedCluster(cluster.snapshotId(), cluster.clusterKey(),
                cluster.entryIds()))
            .toList();
    }
}
```

- [ ] **Step 7: Запустить — зелёный + полный verify**

```bash
./mvnw -q test -Dtest='SubmitGlobalEntryServiceTest,WithdrawGlobalEntryServiceTest,AdvanceGlobalCompetitionServiceTest,GlobalQueryServiceTest,*QualificationEpochRepositoryContractTest,UserDirectoryFacadeTest'
./mvnw -q verify
```

Ожидание: PASS; контекст IT стартует (все порты имеют реализации, сервисы ничего не триггерят — in-адаптеров ещё нет).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/plantarena/tournaments src/main/java/com/plantarena/identity \
  src/main/java/com/plantarena/config/TournamentsWiringConfig.java \
  src/test/java/com/plantarena/tournaments src/test/java/com/plantarena/identity
git commit -m "feat(global): application — подача/снятие, advance границ, запросы; ACL координат и кластеризации"
```

---

### Task 7: in-адаптеры — REST `/global*`, права голоса по scope, demo-ручка, poller, bootstrap; зелёный `GlobalApiIT`

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/GlobalController.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/SubmitGlobalEntryRequest.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/GlobalEntryResponse.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/GlobalInfoResponse.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/GlobalClusterResponse.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/GlobalLeaderboardResponse.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/jobs/GlobalBoundaryPoller.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/jobs/GlobalCompetitionBootstrap.java`
- Modify: `src/main/java/com/plantarena/tournaments/application/VotingService.java`
- Modify: `src/main/java/com/plantarena/tournaments/adapter/in/web/DemoJobsController.java`
- Modify: `src/main/java/com/plantarena/tournaments/adapter/in/web/TournamentsExceptionHandler.java`
- Test: `src/test/java/com/plantarena/tournaments/application/VotingServiceTest.java` (кейсы глобальных окон)

**Interfaces:**
- Consumes: use case-порты Task 6; `CurrentActorProvider`, `PaginationParams`, `ApiError`/`respond(...)` (существуют).
- Produces: REST из раздела 13 (таблица «Глобальный турнир»); `VotingService` ветвление прав по `window.scope()`.

- [ ] **Step 1: `VotingService` — права по scope**

Заменить метод `requireVoter` и его вызовы (в `cast`/`remove`/`myVote` передавать `window`):

```java
    private void requireVoter(CurrentActor actor, Tournament tournament, VotingWindow window) {
        if (window.scope() == WindowScope.PRIVATE) {
            accessPolicy.requireTournamentViewer(actor, tournament,
                visibleBeyondOrganizer(actor, tournament.id()));
            if (!entries.existsByTournamentIdAndUserId(tournament.id(), actor.userId())) {
                throw new AccessDeniedException(
                    "Голосовать может только участник, допущенный к старту (допущение 9)");
            }
            return;
        }
        // глобальные окна (раздел 2): любой идентифицированный пользователь;
        // гость — 401 (GUEST — итерация 8)
        accessPolicy.requireIdentified(actor);
    }
```

(+ импорт `WindowScope`; вызовы: `requireVoter(actor, findTournament(window.tournamentId()), window)`.)

В `VotingServiceTest` добавить кейсы: в квалификационном окне (`VotingWindow.openQualification`) голос постороннего пользователя — разрешён (score меняется), самоголосование — 403, гость — 401; в private-окне — прежние правила (существующие кейсы не меняются).

- [ ] **Step 2: REST-контроллер и DTO**

`src/main/java/com/plantarena/tournaments/adapter/in/web/SubmitGlobalEntryRequest.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Вход DTO подачи глобальной заявки (раздел 13). */
public record SubmitGlobalEntryRequest(@NotNull UUID plantId) {
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/GlobalEntryResponse.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import java.time.Instant;
import java.util.UUID;

/** Глобальное участие (раздел 13). */
public record GlobalEntryResponse(UUID id, UUID plantId, String status, Instant joinedAt) {

    static GlobalEntryResponse from(
            com.plantarena.tournaments.application.port.in.SubmitGlobalEntryUseCase.GlobalEntryView view) {
        return new GlobalEntryResponse(view.id(), view.plantId(), view.status(),
            view.joinedAt());
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/GlobalInfoResponse.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import java.time.Instant;
import java.util.UUID;

/** Публичная конфигурация глобального турнира (раздел 13). */
public record GlobalInfoResponse(long epochDurationSeconds, long finalWindowDurationSeconds,
                                 EpochInfo currentEpoch, WindowInfo currentFinalWindow) {

    record EpochInfo(UUID id, int sequence, Instant opensAt, Instant closesAt) {
    }

    record WindowInfo(UUID id, int sequence, Instant opensAt, Instant closesAt) {
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/GlobalClusterResponse.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import java.time.Instant;
import java.util.UUID;

/** Кластер текущей эпохи (раздел 13): без приватных данных. */
public record GlobalClusterResponse(UUID clusterId, String clusterKey, UUID windowId,
                                    int memberCount, Instant closesAt) {
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/GlobalLeaderboardResponse.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Лидерборд глобального окна (раздел 8, алгоритм 9). */
public record GlobalLeaderboardResponse(String scope, UUID windowId, Instant closesAt,
                                        Instant asOf, List<Item> items) {

    record Item(UUID entryId, UUID userId, long score) {
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/GlobalController.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.shared.web.PaginationParams;
import com.plantarena.tournaments.application.port.in.AdvanceGlobalCompetitionUseCase;
import com.plantarena.tournaments.application.port.in.GetGlobalInfoUseCase;
import com.plantarena.tournaments.application.port.in.GetGlobalLeaderboardUseCase;
import com.plantarena.tournaments.application.port.in.GetMyGlobalEntryUseCase;
import com.plantarena.tournaments.application.port.in.ListGlobalClustersUseCase;
import com.plantarena.tournaments.application.port.in.SubmitGlobalEntryUseCase;
import com.plantarena.tournaments.application.port.in.WithdrawGlobalEntryUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ручки глобального турнира (раздел 13). Конфигурация/кластеры/лидерборды —
 * публичны (без идентификации); подача/снятие/своё участие — U. Контроллер
 * обращается только к входным портам application.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "global")
public class GlobalController {

    private final GetGlobalInfoUseCase globalInfo;
    private final ListGlobalClustersUseCase listClusters;
    private final GetGlobalLeaderboardUseCase leaderboard;
    private final SubmitGlobalEntryUseCase submitEntry;
    private final WithdrawGlobalEntryUseCase withdrawEntry;
    private final GetMyGlobalEntryUseCase myEntry;
    private final CurrentActorProvider currentActorProvider;

    public GlobalController(GetGlobalInfoUseCase globalInfo,
                            ListGlobalClustersUseCase listClusters,
                            GetGlobalLeaderboardUseCase leaderboard,
                            SubmitGlobalEntryUseCase submitEntry,
                            WithdrawGlobalEntryUseCase withdrawEntry,
                            GetMyGlobalEntryUseCase myEntry,
                            CurrentActorProvider currentActorProvider) {
        this.globalInfo = globalInfo;
        this.listClusters = listClusters;
        this.leaderboard = leaderboard;
        this.submitEntry = submitEntry;
        this.withdrawEntry = withdrawEntry;
        this.myEntry = myEntry;
        this.currentActorProvider = currentActorProvider;
    }

    @GetMapping("/global")
    @Operation(operationId = "global-get-info",
        summary = "Конфигурация и текущие окна глобального турнира (публично)")
    public GlobalInfoResponse info() {
        GetGlobalInfoUseCase.GlobalInfo info = globalInfo.globalInfo();
        return new GlobalInfoResponse(info.epochDurationSeconds(),
            info.finalWindowDurationSeconds(),
            info.currentEpoch() == null ? null
                : new GlobalInfoResponse.EpochInfo(info.currentEpoch().id(),
                    info.currentEpoch().sequence(), info.currentEpoch().opensAt(),
                    info.currentEpoch().closesAt()),
            info.currentFinalWindow() == null ? null
                : new GlobalInfoResponse.WindowInfo(info.currentFinalWindow().id(),
                    info.currentFinalWindow().sequence(), info.currentFinalWindow().opensAt(),
                    info.currentFinalWindow().closesAt()));
    }

    @PostMapping("/global/entries")
    @Operation(operationId = "global-submit-entry",
        summary = "Подать одобренное растение в очередь следующего отбора (U)")
    public ResponseEntity<GlobalEntryResponse> submit(
            @Valid @RequestBody SubmitGlobalEntryRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        SubmitGlobalEntryUseCase.GlobalEntryView view = submitEntry.submit(actor,
            request.plantId());
        return ResponseEntity
            .created(URI.create("/api/v1/global/entries/" + view.id()))
            .body(GlobalEntryResponse.from(view));
    }

    @GetMapping("/me/global-entry")
    @Operation(operationId = "global-get-my-entry",
        summary = "Текущее активное глобальное участие или 404 (U)")
    public ResponseEntity<GlobalEntryResponse> myEntry() {
        CurrentActor actor = currentActorProvider.currentActor();
        return myEntry.findActive(actor)
            .map(view -> ResponseEntity.ok(GlobalEntryResponse.from(view)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/global/entries/{entryId}")
    @Operation(operationId = "global-withdraw-entry",
        summary = "Снять заявку из очереди до включения в окно (владелец)")
    public ResponseEntity<Void> withdraw(@PathVariable UUID entryId) {
        CurrentActor actor = currentActorProvider.currentActor();
        withdrawEntry.withdraw(actor, entryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/global/clusters")
    @Operation(operationId = "global-list-clusters",
        summary = "Кластеры текущей эпохи отбора (публично)")
    public List<GlobalClusterResponse> clusters(@RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size,
                                                org.springframework.http.HttpServletResponse response) {
        ListGlobalClustersUseCase.ClusterListResult result =
            listClusters.list(PaginationParams.of(page, size));
        response.setHeader("X-Total-Count", String.valueOf(result.total()));
        return result.items().stream()
            .map(cluster -> new GlobalClusterResponse(cluster.clusterId(), cluster.clusterKey(),
                cluster.windowId(), cluster.memberCount(), cluster.closesAt()))
            .toList();
    }

    @GetMapping("/global/leaderboard")
    @Operation(operationId = "global-get-final-leaderboard",
        summary = "Рейтинг текущего финального окна (публично, scope=FINAL)")
    public GlobalLeaderboardResponse finalLeaderboard(
            @RequestParam(defaultValue = "FINAL") String scope,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        if (!"FINAL".equals(scope)) {
            throw new UnknownGlobalScopeException(
                "Неизвестный scope: " + scope + " (допустимо: FINAL)");
        }
        GetGlobalLeaderboardUseCase.GlobalLeaderboard board =
            leaderboard.finalLeaderboard(PaginationParams.of(page, size));
        return toResponse(board);
    }

    @GetMapping("/global/clusters/{clusterId}/leaderboard")
    @Operation(operationId = "global-get-cluster-leaderboard",
        summary = "Отбор конкретного кластера текущей эпохи (публично)")
    public GlobalLeaderboardResponse clusterLeaderboard(@PathVariable UUID clusterId,
                                                        @RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size) {
        GetGlobalLeaderboardUseCase.GlobalLeaderboard board =
            leaderboard.clusterLeaderboard(clusterId, PaginationParams.of(page, size));
        return toResponse(board);
    }

    private GlobalLeaderboardResponse toResponse(
            GetGlobalLeaderboardUseCase.GlobalLeaderboard board) {
        return new GlobalLeaderboardResponse(board.scope(), board.windowId(), board.closesAt(),
            board.asOf(), board.items().stream()
                .map(item -> new GlobalLeaderboardResponse.Item(item.entryId(), item.userId(),
                    item.score()))
                .toList());
    }
}
```

(если существующие контроллеры возвращают `X-Total-Count` иначе — через `ResponseEntity<List<...>>` с заголовком — привести к фактическому паттерну `TournamentController`/`TagController`.)

- [ ] **Step 3: Demo-ручка, poller, bootstrap, обработчик ошибок**

`DemoJobsController` — добавить зависимость `AdvanceGlobalCompetitionUseCase advanceGlobalCompetition` и расширить ответ `run-due`:

```java
        AdvanceGlobalCompetitionUseCase.AdvanceReport report =
            advanceGlobalCompetition.advance(clock.instant());
        return Map.of(
            "processed", startTournament.startDue(clock.instant(), 10),
            "closedWindows", closeVotingWindow.closeDue(clock.instant(), 10),
            "globalQualificationClosed", report.qualificationClosed(),
            "globalFinalClosed", report.finalClosed(),
            "globalFinalsOpened", report.finalsOpened(),
            "globalEpochsOpened", report.epochsOpened());
```

`src/main/java/com/plantarena/tournaments/adapter/in/jobs/GlobalBoundaryPoller.java`:

```java
package com.plantarena.tournaments.adapter.in.jobs;

import com.plantarena.tournaments.application.port.in.AdvanceGlobalCompetitionUseCase;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Опрос границ глобального турнира (раздел 8): fixedDelay 2с. Тот же use
 * case, что demo-ручка; идемпотентность по статусам — рестарт приложения не
 * теряет окна и не убивает растения повторно (раздел 12.3).
 */
@Component
public class GlobalBoundaryPoller {

    private static final Logger log = LoggerFactory.getLogger(GlobalBoundaryPoller.class);

    private final AdvanceGlobalCompetitionUseCase advanceGlobalCompetition;
    private final Clock clock;

    public GlobalBoundaryPoller(AdvanceGlobalCompetitionUseCase advanceGlobalCompetition,
                                Clock clock) {
        this.advanceGlobalCompetition = advanceGlobalCompetition;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        try {
            advanceGlobalCompetition.advance(clock.instant());
        } catch (RuntimeException e) {
            log.error("Цикл границ глобального турнира не удался (продолжаем): {}",
                e.getMessage());
        }
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/jobs/GlobalCompetitionBootstrap.java`:

```java
package com.plantarena.tournaments.adapter.in.jobs;

import com.plantarena.tournaments.application.port.in.EnsureGlobalCompetitionUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Создание единственной записи глобального турнира при старте (раздел 8,
 * ADR-012): идемпотентно, повторный запуск дубликат не создаёт. Работает во
 * всех профилях (после Flyway).
 */
@Component
public class GlobalCompetitionBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GlobalCompetitionBootstrap.class);

    private final EnsureGlobalCompetitionUseCase ensureGlobalCompetition;

    public GlobalCompetitionBootstrap(EnsureGlobalCompetitionUseCase ensureGlobalCompetition) {
        this.ensureGlobalCompetition = ensureGlobalCompetition;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureGlobalCompetition.ensure();
        log.info("Глобальный турнир гарантирован (id фиксирован, дубликатов нет)");
    }
}
```

`TournamentsExceptionHandler` — добавить обработчики (по образцу существующих, `retryAt` — null):

```java
    @ExceptionHandler(SubmittedPlantNotFoundException.class)
    public ResponseEntity<ApiError> submittedPlantNotFound(SubmittedPlantNotFoundException e,
                                                           HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "PLANT_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(GlobalEntryNotFoundException.class)
    public ResponseEntity<ApiError> globalEntryNotFound(GlobalEntryNotFoundException e,
                                                        HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "GLOBAL_ENTRY_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(ClusterNotFoundException.class)
    public ResponseEntity<ApiError> clusterNotFound(ClusterNotFoundException e,
                                                    HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "CLUSTER_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(PlantNotApprovedException.class)
    public ResponseEntity<ApiError> plantNotApproved(PlantNotApprovedException e,
                                                     HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "PLANT_NOT_APPROVED", e.getMessage(), request);
    }

    @ExceptionHandler(LocationRequiredException.class)
    public ResponseEntity<ApiError> locationRequired(LocationRequiredException e,
                                                     HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "LOCATION_REQUIRED", e.getMessage(), request);
    }

    @ExceptionHandler(ActiveGlobalEntryExistsException.class)
    public ResponseEntity<ApiError> activeGlobalEntryExists(ActiveGlobalEntryExistsException e,
                                                             HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "GLOBAL_ENTRY_ACTIVE", e.getMessage(), request);
    }

    @ExceptionHandler(GlobalEntryNotWithdrawableException.class)
    public ResponseEntity<ApiError> globalEntryNotWithdrawable(
            GlobalEntryNotWithdrawableException e, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "ENTRY_IN_WINDOW", e.getMessage(), request);
    }

    @ExceptionHandler(UnknownGlobalScopeException.class)
    public ResponseEntity<ApiError> unknownGlobalScope(UnknownGlobalScopeException e,
                                                       HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "GLOBAL_SCOPE_UNKNOWN", e.getMessage(), request);
    }
```

(+ импорты новых исключений.)

- [ ] **Step 4: Запустить — зелёный приёмочный + полный verify**

```bash
./mvnw -q verify -Dit.test='GlobalApiIT' -DfailIfNoTests=false
./mvnw -q verify
```

Ожидание: `GlobalApiIT` PASS (обе проверки из Task 1); полный verify зелёный (существующие IT не затронуты: GLOBAL скрыт из `/tournaments`, приватные окна ведут себя как раньше).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/plantarena/tournaments src/test/java/com/plantarena/tournaments/application/VotingServiceTest.java
git commit -m "feat(global): REST /global*, права голоса по scope, demo-ручка и scheduler границ, bootstrap"
```

---

### Task 8: Идемпотентность и конкуренция — `GlobalIdempotencyIT`

**Files:**
- Test: `src/test/java/com/plantarena/tournaments/GlobalIdempotencyIT.java`

**Interfaces:**
- Consumes: REST Task 7; `@TestPropertySource`-паттерн `GlobalApiIT`; конкурентный паттерн `VotingConcurrencyIT` (CountDownLatch + потоки).

- [ ] **Step 1: Написать IT**

`src/test/java/com/plantarena/tournaments/GlobalIdempotencyIT.java`:

```java
package com.plantarena.tournaments;

import com.jayway.jsonpath.JsonPath;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.support.DeterministicPlantClassifier;
import com.plantarena.support.AbstractIntegrationTest;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Раздел 12.3 для глобального режима: повторное закрытие ничего не начисляет
 * и не убивает повторно (рестарт без повторной гибели); конкурентная подача
 * одного пользователя — ровно одно активное участие (частичный уникальный
 * индекс, раздел 11).
 */
@TestPropertySource(properties = {
    "plantarena.global.epoch-duration=PT3S",
    "plantarena.global.final-window-duration=PT3S",
    "plantarena.geo.geohash-precision=4"
})
@DisplayName("Идемпотентность границ и конкуренция глобальных заявок")
class GlobalIdempotencyIT extends AbstractIntegrationTest {

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
    @DisplayName("повторный проход границ не убивает и не запрещает повторно (раздел 12.3)")
    void рестарт_без_повторной_гибели() throws Exception {
        UUID u1 = createUserAsAdmin("idem-u1@example.com", "I1");
        UUID u2 = createUserAsAdmin("idem-u2@example.com", "I2");
        setLocation(u1, 55.7558, 37.6173);
        setLocation(u2, 55.7558, 37.6173);
        UUID plant1 = approvedPlantOf(u1, "Идем u1");
        UUID plant2 = approvedPlantOf(u2, "Идем u2");
        UUID entry1 = submitGlobalEntry(u1, plant1);
        UUID entry2 = submitGlobalEntry(u2, plant2);

        // эпоха открыта; u1 побеждает (голос), u2 выбывает
        runDue();
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> mockMvc.perform(get("/api/v1/me/global-entry")
                    .header(DEMO_HEADER, u1.toString()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .jsonPath("$.status").value("QUALIFYING")));
        String clusters = mockMvc.perform(get("/api/v1/global/clusters"))
            .andReturn().getResponse().getContentAsString();
        UUID windowId = UUID.fromString((String) ((java.util.Map<String, Object>)
            ((List<?>) JsonPath.read(clusters, "$")).get(0)).get("windowId"));
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + entry1 + "/vote")
                .header(DEMO_HEADER, u1.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isOk());
        // u2 не может голосовать за себя; голосует за entry1 — не обязательно
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> mockMvc.perform(get("/api/v1/plants/" + plant2)
                    .header(DEMO_HEADER, u2.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .jsonPath("$.lifeStatus").value("DEAD")));

        // до повторов: ровно один активный запрет COOLDOWN у u2
        Long restrictionsBefore = restrictionCount(u2);
        String finalWindowBefore = mockMvc.perform(get("/api/v1/global/leaderboard?scope=FINAL"))
            .andReturn().getResponse().getContentAsString();

        // «рестарт»: несколько повторных проходов подряд
        for (int i = 0; i < 3; i++) {
            runDue();
        }

        assertThat(restrictionCount(u2)).isEqualTo(restrictionsBefore); // не запретили повторно
        String finalWindowAfter = mockMvc.perform(get("/api/v1/global/leaderboard?scope=FINAL"))
            .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.read(finalWindowAfter, "$.windowId"))
            .isEqualTo(JsonPath.read(finalWindowBefore, "$.windowId")); // финал не пересоздан
    }

    @Test
    @DisplayName("конкурентная подача одного пользователя: ровно одно 201, остальные 409 (раздел 11)")
    void конкурентная_подача() throws Exception {
        UUID user = createUserAsAdmin("idem-race@example.com", "Race");
        setLocation(user, 55.7558, 37.6173);
        List<UUID> plants = IntStream.range(0, 3)
            .mapToObj(i -> approvedPlantOf(user, "Гонка " + i)).toList();

        int threads = plants.size();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = plants.stream()
            .map(plantId -> executor.submit(() -> {
                start.await();
                return mockMvc.perform(post("/api/v1/global/entries")
                        .header(DEMO_HEADER, user.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plantId\":\"%s\"}".formatted(plantId)))
                    .andReturn().getResponse().getStatus();
            }))
            .toList();
        start.countDown();
        int created = 0;
        int conflicts = 0;
        for (Future<Integer> result : results) {
            int status = result.get(30, TimeUnit.SECONDS);
            if (status == 201) {
                created++;
            } else if (status == 409) {
                conflicts++;
            }
        }
        executor.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        Long active = jdbcTemplate.queryForObject(
            "select count(*) from tournaments.tournament_entry "
                + "where user_id = ? and status in ('QUEUED','QUALIFYING','FINAL_PENDING','FINALIST')",
            Long.class, user);
        assertThat(active).isEqualTo(1L);
    }

    // ---------- helpers (как в GlobalApiIT) ----------

    private Long restrictionCount(UUID userId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from plants.image_restriction where owner_id = ?",
            Long.class, userId);
    }

    private void setLocation(UUID user, double latitude, double longitude) throws Exception {
        mockMvc.perform(put("/api/v1/me/location")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":%s,\"longitude\":%s}".formatted(latitude, longitude)))
            .andExpect(status().isOk());
    }

    private UUID submitGlobalEntry(UUID user, UUID plantId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/global/entries")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plantId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void runDue() throws Exception {
        mockMvc.perform(post("/api/v1/internal/demo/jobs/run-due")
                .header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isOk());
    }

    private UUID approvedPlantOf(UUID user, String title) throws Exception {
        UUID assetId = uploadAs(user);
        String body = mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":\"%s\",\"title\":\"%s\"}".formatted(assetId, title)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID plantId = UUID.fromString(JsonPath.read(body, "$.id"));
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> mockMvc.perform(get("/api/v1/plants/" + plantId + "/moderation")
                    .header(DEMO_HEADER, user.toString()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .jsonPath("$.moderationStatus").value("APPROVED")));
        return plantId;
    }

    private UUID uploadAs(UUID userId) throws Exception {
        byte[] content;
        try (InputStream in = getClass().getResourceAsStream("/media/reference/green-8x8.png")) {
            content = in.readAllBytes();
        }
        String response = mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("file", "reference.png",
                    MediaType.IMAGE_PNG_VALUE, content))
                .header(DEMO_HEADER, userId.toString()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
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

(имя таблицы/колонок `plants.image_restriction` — сверить с `plants/V2__plants.sql`; при отличии использовать фактические.)

- [ ] **Step 2: Запустить — зелёный + полный verify**

```bash
./mvnw -q verify -Dit.test='GlobalIdempotencyIT' -DfailIfNoTests=false
./mvnw -q verify
```

Ожидание: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/plantarena/tournaments/GlobalIdempotencyIT.java
git commit -m "test(global): идемпотентность границ (рестарт без повторной гибели) и конкурентная подача"
```

---

### Task 9: Docs — ADR-012, глоссарий, aggregates, context map, README

**Files:**
- Create: `docs/domain/adr/ADR-012-global-competition.md`
- Modify: `docs/domain/glossary.md`
- Modify: `docs/domain/aggregates.md`
- Modify: `docs/domain/context-map.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: всё выше; стиль существующих ADR (ADR-010/011 — «работает только в монолите» + план лабы №2/№4).

- [ ] **Step 1: ADR-012**

`docs/domain/adr/ADR-012-global-competition.md` (по структуре существующих ADR: Контекст / Решение / Последствия):

```markdown
# ADR-012: глобальный турнир — запись-якорь, эпохи и границы времени

Статус: принято (итерация 7). Контекст: разделы 8, 11, 12.3 требований.

## Решение

1. `GlobalCompetition` хранится как единственная запись `tournament` типа
   GLOBAL с фиксированным UUID `00000007-10ba-4000-8000-000000000001`
   (создаётся идемпотентным bootstrap-раннером, creator — системный UUID).
   Инвариант «GLOBAL никогда не FINISHED» — в домене (`Tournament.finish`
   кидает исключение). Публичное представление — `GET /global`; из
   `/tournaments` GLOBAL скрыт (404).
2. Временные границы глобального режима — один use case
   `AdvanceGlobalCompetitionUseCase.advance(now)` с фиксированным порядком
   (алгоритм 6): закрыть due-квалификацию → закрыть due-финал → открыть
   следующий финал → открыть следующую эпоху. Scheduler (fixedDelay 2с) и
   demo-ручка вызывают один и тот же use case.
3. Отступление «одна tx — один агрегат» (работает только в монолите):
   закрытие квалификационного/финального окна — окно + entries + команды
   plants (гибель COOLDOWN 24 ч, освобождение резерва) + события в одной tx;
   открытие эпохи — epoch + entries + команда geo + окна в одной tx.
   Растения обрабатываются в устойчивом порядке по plantId (раздел 12.3).
   План лабы №2: saga с идемпотентными командами (reservationId, статус
   окна как idempotency key); лабы №4 — outbox/inbox.
4. Идемпотентность: закрытие — по статусу окна (CLOSED — no-op), открытие
   финала/эпохи — по наличию OPEN-записи (частичные уникальные индексы
   `voting_window_one_open_final_uidx`, `qualification_epoch_one_open_uidx`).
   Рестарт приложения не теряет окна и не убивает растения повторно
   (гарантирует `GlobalIdempotencyIT`).
5. geo — Upstream: координаты приходят командой `ClusterAssignment`
   (кластеризация + неизменные снимки), identity читает только tournaments
   через `UserDirectory.findLocation` (ACL `ParticipantLocationsGateway`).
   Тайминги — `plantarena.global.*`, точность geohash —
   `plantarena.geo.geohash-precision`; версия политики фиксируется в снимках.
6. Уникальность участий: private-пара (tournament_id, user_id) — частичный
   индекс по private-статусам; одно активное глобальное участие на
   пользователя — частичный индекс по QUEUED/QUALIFYING/FINAL_PENDING/FINALIST
   (допущение 5: после гибели можно сразу подать другое изображение).
```

- [ ] **Step 2: Глоссарий, aggregates, context map**

`docs/domain/glossary.md` — добавить строки (формат таблицы раздела 4.1):

```markdown
| Очередь глобального турнира | `EntryStatus.QUEUED` | tournaments | Принятая заявка, ожидающая следующей эпохи |
| Квалификационное окно | `VotingWindow` scope QUALIFICATION | tournaments | Окно отбора одного кластера эпохи |
| Финальное окно | `VotingWindow` scope FINAL | tournaments | Непрерывный финал глобального турнира |
| Продвижение | `ParticipantResult.PROMOTED` | tournaments | Итог top-1 квалификации: проход в финал |
| Снятие заявки | `TournamentEntry.withdraw()` | tournaments | QUEUED → WITHDRAWN до включения в окно |
| Ячейка/ключ кластера | `clusterKey` (geohash) | geo | Обобщённое описание кластера наружу |
```

`docs/domain/aggregates.md` — добавить `QualificationEpoch` (корень, инварианты: интервал, одна OPEN-эпоха, закрытие после закрытия всех окон; тесты `QualificationEpochTest`), `ClusterSnapshot` (неизменность после фиксации; `ClusterSnapshotTest`), расширить `TournamentEntry` (глобальная state machine; `TournamentEntryGlobalTest`), `VotingWindow` (scope, минимум один участник глобальных окон, PROMOTED; `VotingWindowGlobalTest`), `Tournament` (GLOBAL и запрет finish; `TournamentTest`).

`docs/domain/context-map.md` — добавить в таблицу взаимодействий: tournaments → identity (`UserDirectory.findLocation`, запрос, синхронно; лаба №2 — Feign), tournaments → geo (`ClusterAssignment.assignClusters`, команда, синхронно; лаба №2 — Feign + идемпотентный повтор по epochId), scheduler → `AdvanceGlobalCompetitionUseCase`.

- [ ] **Step 3: README**

`README.md` — заменить строку «Глобальный турнир и geo-кластеры — итерация 7» раздела «Турниры» на раздел по образцу существующих:

```markdown
## Глобальный турнир (global)

Постоянный глобальный турнир (раздел 8): заявка `POST /api/v1/global/entries`
(своё APPROVED-растение, координаты в профиле обязательны — `PUT /me/location`;
одно активное участие на пользователя) → очередь → эпоха отбора раз в
`plantarena.global.epoch-duration` (по умолчанию 24 ч): гео-кластеры
(geohash, `plantarena.geo.geohash-precision`), квалификационное окно на
непустой кластер. Закрытие: top-1 — в финал (PROMOTED), остальные — гибель +
суточный запрет совпавшей картинки (COOLDOWN 24 ч, `retryAt`) + освобождение
резерва. Финал — непрерывные окна `final-window-duration`: из n ≥ 2 выбывает
max(1, floor(n/2)) худших, единственный лидер остаётся; новых финалистов
включает следующее окно. Порядок одновременных границ фиксирован (алгоритм 6);
scheduler идемпотентен — рестарт не убивает повторно (ADR-012).

- Конфигурация и текущие окна — `GET /api/v1/global` (публично).
- Кластеры эпохи — `GET /api/v1/global/clusters`; отбор кластера —
  `GET /api/v1/global/clusters/{id}/leaderboard`; финал —
  `GET /api/v1/global/leaderboard?scope=FINAL` (scope, windowId, closesAt, asOf).
- Своё участие — `GET /api/v1/me/global-entry`; снятие из очереди —
  `DELETE /api/v1/global/entries/{id}` (только QUEUED, иначе 409).
- Голосовать в глобальных окнах может любой идентифицированный пользователь
  (гость — итерация 8); самоголосование запрещено.
- Диагностика: `POST /api/v1/internal/demo/jobs/run-due` дополнительно
  продвигает границы (ответ: globalQualificationClosed/globalFinalClosed/
  globalFinalsOpened/globalEpochsOpened).
```

- [ ] **Step 4: Финальный verify + Commit**

```bash
./mvnw -q verify
```

Ожидание: зелёный; покрытие ≥ 70% (gate).

```bash
git add docs/domain/adr/ADR-012-global-competition.md docs/domain/glossary.md \
  docs/domain/aggregates.md docs/domain/context-map.md README.md
git commit -m "docs(global): ADR-012, глоссарий, агрегаты, context map, README итерации 7"
```

---

## Definition of Done (итерация 7)

- `./mvnw verify` зелёный: unit + application + контрактные + ArchUnit + IT (`GlobalApiIT`, `GlobalIdempotencyIT`, остальные) + JaCoCo ≥ 70%.
- Раздел 8 (алгоритмы 1–9) покрыт именованными тестами: очередь (1), эпоха/кластеры (2), top-1/гибель/COOLDOWN (3), единственный в ячейке (4), FINAL_PENDING (5), порядок границ (6), floor(n/2)/лидер/n=0 (7), 24 ч финального запрета (8), лидерборды со scope/windowId/closesAt/asOf (9).
- Scheduler идемпотентен: повторные проходы не меняют результаты (Task 8).
- Docs обновлены (ADR-012, глоссарий, aggregates, context map, README).
