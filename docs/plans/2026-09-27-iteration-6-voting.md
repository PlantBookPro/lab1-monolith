# Итерация 6 (голосование и закрытие раундов) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Контекст `tournaments`: агрегат `VotingWindow` (+ `WindowParticipant`, `Vote`) — голосование с дельтами (+1/−1/±2) и блокировкой окна (раздел 12.1), доменные сервисы `EliminationAlgorithm`/`RoundElimination` и `ParticipantRanking` (tie-break), идемпотентное закрытие окон с гибелью/запретами/освобождением резервов и следующим раундом (раздел 12.3), `Tournament.finish` + `TournamentEntry.eliminate/declareWinner`, первое окно при старте, события `EntryEliminated`/`TournamentFinished`, REST `/windows/*/vote`, `/tournaments/{id}/rounds|leaderboard|results`, миграция V3 схемы `tournaments`, приёмочный `VotingApiIT` и конкурентный `VotingConcurrencyIT`, docs (ADR-011, глоссарий, aggregates, context map, README).

**Architecture:** Модульный монолит, bounded context `tournaments` (api/domain/application/adapter) — downstream от identity и plants (раздел 4.3). Один агрегат `VotingWindow`: голосование и закрытие сериализуются `SELECT ... FOR UPDATE` по строке окна (порт `VotingWindowRepository.findByIdForUpdate`), порядок блокировок окно → window_participant → vote; проверка времени — после блокировки. Закрытие — один use case `CloseVotingWindowUseCase.closeDue` для scheduler'а (`adapter.in.jobs`, fixedDelay 2с) и demo-ручки; межконтекстные последствия (гибель PERMANENT + release резерва через `PlantLifecycleGateway`/`PlantEligibilityGateway`) — в одной tx (ADR-011), растения в устойчивом порядке по `plantId`. `VotingSubject` — только USER (GUEST — итерация 8). Дизайн: `docs/specs/2026-09-27-iteration-6-voting-design.md`.

**Tech Stack:** Java 21, Spring Boot 4.0.8 (MVC, Data JPA, `@EnableScheduling` — уже включён), PostgreSQL 17.5 + Flyway по контекстам (ADR-003), Testcontainers 2.0.5, ArchUnit 1.5.0, JaCoCo 0.8.15, Awaitility (test).

## Global Constraints

- Ветка `feat/iteration-6-voting` (от `main`); Conventional Commits; тесты коммитируются вместе с реализацией или раньше; красные тесты в `main` не попадают.
- TDD (раздел 14): сначала красный приёмочный `VotingApiIT` (Task 1), затем внутренний цикл red→green→refactor (домен → application → хранилище → адаптеры).
- `./mvnw verify` — единственная команда проверки; совокупный LINE coverage ≥ 70% (gate).
- Все ID — UUID; время — `Instant`/UTC из внедрённого `Clock`; домен получает `Instant now` аргументом, не `Instant.now()`.
- Enum — VARCHAR + CHECK в миграциях, маппинг явный (`.name()`), без `EnumType.STRING`; Hibernate `ddl-auto=validate`; Flyway — единственный источник структуры.
- `version` (JPA `@Version`) — у `VotingWindow` (конкурентное сохранение при голосовании); `WindowParticipant`/`Vote` — дети агрегата, без собственной version; `TournamentEntry` — без version (меняется только в tx закрытия под блокировкой окна).
- Границы контекстов — только через `api` и только из адаптеров (ArchUnit; новое `tournaments.adapter.out.plants.InProcessPlantLifecycle` не нарушает правила). `tournaments.domain`/`application` не импортируют чужие контексты.
- Отступление «одна tx — один агрегат» (раздел 12.3): закрытие окна (окно + entries + tournament + команды plants) — ADR-011 (Task 9) с планом saga лабы №2. Голосование — одна tx, один агрегат (раздел 12.1), отступлением не является.
- **Урок итерации 2:** абстрактные базовые классы контрактных тестов репозиториев обязаны нести `@Transactional` на себе.
- Новые понятия — сначала в `docs/domain/glossary.md` (Окно/Участник окна/Голос/Субъект голосования уже есть; добавить «Дельта голоса»), изменения правил — сначала в docs (aggregates, ADR-011), затем код (Task 9 замыкает итерацию).
- REST (раздел 13): PUT с телом — 200 с новым score; DELETE — 204, идемпотентен; page/size по умолчанию 20, диапазон 1–50 (общий `PaginationParams`); rounds/leaderboard/results с `X-Total-Count`; скрытое — 404, конфликты — 409, отсутствие права — 403, гость на защищённой ручке — 401; уникальные operationId с префиксом контекста (`voting-*`, `tournaments-*`).
- Среда: macOS/zsh; рабочая директория `lab1-monolith/`; scratch-файлы диагностики не коммитировать.

## Карта файлов итерации

```text
src/main/java/com/plantarena/tournaments/
├── api/event/
│   ├── EntryEliminatedEvent.java                       НОВОЕ (Task 5)
│   └── TournamentFinishedEvent.java                    НОВОЕ (Task 5)
├── domain/
│   ├── VoteValue.java                                  НОВОЕ: LIKE/DISLIKE + transitionDelta (Task 2)
│   ├── VotingSubject.java                              НОВОЕ: USER (GUEST — итерация 8) (Task 2)
│   ├── WindowStatus.java                               НОВОЕ: OPEN/CLOSED (Task 2)
│   ├── ParticipantResult.java                          НОВОЕ: ACTIVE/SURVIVED/ELIMINATED/WINNER (Task 2)
│   ├── WindowParticipant.java                          НОВОЕ (Task 2)
│   ├── Vote.java                                       НОВОЕ (Task 2)
│   ├── VotingWindow.java                               НОВОЕ: агрегат (Task 2 — голоса, Task 3 — close)
│   ├── EliminationAlgorithm.java                       НОВОЕ: стратегия (Task 3)
│   ├── RoundElimination.java                           НОВОЕ (Task 3)
│   ├── EliminationAlgorithms.java                      НОВОЕ: фабрика по kind (Task 3)
│   ├── ParticipantRanking.java                         НОВОЕ: tie-break (Task 3)
│   ├── VotingWindowRepository.java                     НОВОЕ: порт (Task 2)
│   ├── Tournament.java                                 ИЗМЕНЕНО: finish(now) (Task 3)
│   ├── TournamentEntry.java                            ИЗМЕНЕНО: eliminate/declareWinner (Task 3)
│   └── TournamentEntryRepository.java                  ИЗМЕНЕНО: findById (Task 3)
├── application/
│   ├── VotingService.java                              НОВОЕ: cast/remove/myVote (Task 4)
│   ├── CloseVotingWindowService.java                   НОВОЕ: closeDue (Task 5)
│   ├── VotingQueryService.java                         НОВОЕ: rounds/leaderboard/results (Task 5)
│   ├── StartTournamentService.java                     ИЗМЕНЕНО: первое окно (Task 5)
│   ├── WindowNotFoundException.java                    НОВОЕ (404, Task 4)
│   ├── EntryNotInWindowException.java                  НОВОЕ (404, Task 4)
│   ├── VotingClosedException.java                      НОВОЕ (409, Task 4)
│   ├── SelfVoteForbiddenException.java                 НОВОЕ (403, Task 4)
│   ├── UnknownVoteValueException.java                  НОВОЕ (400, Task 4)
│   ├── port/in/VotingUseCase.java                      НОВОЕ (Task 4)
│   ├── port/in/CloseVotingWindowUseCase.java           НОВОЕ (Task 5)
│   ├── port/in/ListRoundsUseCase.java                  НОВОЕ (Task 5)
│   ├── port/in/GetLeaderboardUseCase.java              НОВОЕ (Task 5)
│   ├── port/in/ListResultsUseCase.java                 НОВОЕ (Task 5)
│   └── port/out/PlantLifecycleGateway.java             НОВОЕ: registerDeath (Task 1)
└── adapter/
    ├── in/web/
    │   ├── VotingController.java                       НОВОЕ: /api/v1/windows/* (Task 7)
    │   ├── VoteRequest.java, VoteResponse.java, MyVoteResponse.java  НОВОЕ (Task 7)
    │   ├── RoundResponse.java, LeaderboardResponse.java, ResultResponse.java  НОВОЕ (Task 7)
    │   ├── TournamentController.java                   ИЗМЕНЕНО: rounds/leaderboard/results (Task 7)
    │   ├── DemoJobsController.java                     ИЗМЕНЕНО: +closedWindows (Task 7)
    │   └── TournamentsExceptionHandler.java            ИЗМЕНЕНО: новые коды (Task 7)
    ├── in/jobs/VotingWindowClosePoller.java            НОВОЕ: @Scheduled(fixedDelay=2с) (Task 7)
    ├── out/plants/InProcessPlantLifecycle.java         НОВОЕ: ACL plants.api.PlantLifecycle (Task 7)
    └── out/persistence/
        ├── VotingWindowJpaEntity.java                  НОВОЕ (Task 6)
        ├── WindowParticipantJpaEntity.java             НОВОЕ (Task 6)
        ├── VoteJpaEntity.java                          НОВОЕ (Task 6)
        ├── VotingWindowJpaRepository.java              НОВОЕ: Spring Data + @Lock (Task 6)
        ├── JpaVotingWindowRepository.java              НОВОЕ: порт + явный маппинг (Task 6)
        └── JpaTournamentEntryRepository.java           ИЗМЕНЕНО: findById (Task 6)

src/main/resources/db/migration/tournaments/V3__voting.sql  НОВОЕ (Task 6)

src/test/java/com/plantarena/tournaments/
├── VotingApiIT.java                                    НОВОЕ (Task 1 — красный, Task 7 — зелёный)
├── VotingConcurrencyIT.java                            НОВОЕ (Task 8)
├── domain/
│   ├── VoteValueTest.java                              НОВОЕ (Task 2)
│   ├── VotingWindowTest.java                           НОВОЕ (Tasks 2–3)
│   ├── RoundEliminationTest.java                       НОВОЕ (Task 3)
│   ├── ParticipantRankingTest.java                     НОВОЕ (Task 3)
│   ├── TournamentTest.java                             ИЗМЕНЕНО: finish (Task 3)
│   └── TournamentEntryTest.java                        ИЗМЕНЕНО: eliminate/declareWinner (Task 3)
├── application/
│   ├── VotingServiceTest.java                          НОВОЕ (Task 4)
│   ├── CloseVotingWindowServiceTest.java               НОВОЕ (Task 5)
│   ├── VotingQueryServiceTest.java                     НОВОЕ (Task 5)
│   ├── StartTournamentServiceTest.java                 ИЗМЕНЕНО: первое окно (Task 5)
│   └── support/
│       ├── InMemoryVotingWindowRepository.java         НОВОЕ (Task 4)
│       └── FakePlantLifecycleGateway.java              НОВОЕ (Task 5)
├── VotingWindowRepositoryContractTest.java             НОВОЕ: абстрактный (Task 6)
└── adapter/out/persistence/JpaVotingWindowRepositoryContractIT.java  НОВОЕ (Task 6)

docs/domain/adr/ADR-011-voting-window-close-tx.md       НОВОЕ (Task 9)
docs/domain/{glossary,aggregates,context-map}.md, README.md  ИЗМЕНЕНО (Task 9)
docs/specs/2026-09-27-iteration-6-voting-design.md      СОЗДАН до плана
```

---

### Task 1: Порт out `PlantLifecycleGateway`, красный приёмочный `VotingApiIT`

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/application/port/out/PlantLifecycleGateway.java`
- Test: `src/test/java/com/plantarena/tournaments/VotingApiIT.java`

**Interfaces:**
- Consumes: `plants.api.PlantLifecycle` (существует, итерация 3), REST итераций 1–5, `AbstractIntegrationTest`, эталонные `green-8x8.png`/`red-8x8.png`, `DeterministicPlantClassifier` (существует в `moderation.support`).
- Produces (для Tasks 4–7): порт `PlantLifecycleGateway { void registerDeath(UUID plantId, RestrictionKind kind, Instant cooldownExpiresAt, String reason, UUID sourceEntryId); enum RestrictionKind { PERMANENT, COOLDOWN } }`; красный `VotingApiIT` (зелёный в Task 7).

- [ ] **Step 1: Порт out `PlantLifecycleGateway`**

`src/main/java/com/plantarena/tournaments/application/port/out/PlantLifecycleGateway.java`:

```java
package com.plantarena.tournaments.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Выходной порт tournaments: гибель растения и запрет его изображения при
 * выбывании (разделы 6, 12.3, Consumer-driven). Адаптер
 * tournaments.adapter.out.plants вызывает plants.api.PlantLifecycle (ACL,
 * раздел 4.3): гибель передаётся командой, а не записью в таблицы plants.
 * Вид запрета выбирает tournaments: PERMANENT — поражение в закрытом
 * турнире, COOLDOWN — в глобальном (итерация 7).
 */
public interface PlantLifecycleGateway {

    /**
     * Зарегистрировать гибель и запрет.
     *
     * @param kind             PERMANENT (без expiresAt) или COOLDOWN (24 ч — итерация 7)
     * @param cooldownExpiresAt обязателен для COOLDOWN, запрещён для PERMANENT
     * @param reason           причина в едином языке (для истории)
     * @param sourceEntryId    участие, из которого последовала гибель
     */
    void registerDeath(UUID plantId, RestrictionKind kind, Instant cooldownExpiresAt,
                       String reason, UUID sourceEntryId);

    enum RestrictionKind {
        PERMANENT, COOLDOWN
    }
}
```

- [ ] **Step 2: Красный приёмочный `VotingApiIT`**

`src/test/java/com/plantarena/tournaments/VotingApiIT.java`:

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Внешний цикл TDD итерации 6: разделы 9 (голоса) и 7 (выбывание, победитель)
 * через HTTP на Testcontainers. Классификатор детерминированный (как в
 * TournamentsApiIT): зелёный PNG → APPROVED. Раунды короткие (5 с), закрытие
 * выполняет scheduler (2 с) — ждём Awaitility. Красный до Task 7 (ручек
 * голосования нет).
 */
@DisplayName("Сценарии разделов 9 и 7: голосование → закрытие раунда → выбывание → победитель")
class VotingApiIT extends AbstractIntegrationTest {

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
    @DisplayName("полный цикл: старт → окно → голоса и дельты → закрытие → выбывание+гибель+запрет → победитель → FINISHED")
    void полный_цикл_до_победителя() throws Exception {
        UUID organizer = adminId();
        UUID u1 = createUserAsAdmin("vote-u1@example.com", "V1");
        UUID u2 = createUserAsAdmin("vote-u2@example.com", "V2");
        UUID u3 = createUserAsAdmin("vote-u3@example.com", "V3");
        UUID stranger = createUserAsAdmin("vote-stranger@example.com", "Stranger");

        UUID tournamentId = createTournamentAsAdmin("Голосование",
            Instant.now().plusSeconds(6), 5, 0.5);
        inviteAsAdmin(tournamentId, u1);
        inviteAsAdmin(tournamentId, u2);
        inviteAsAdmin(tournamentId, u3);
        mockMvc.perform(post("/api/v1/tournaments/" + tournamentId + "/open-registration")
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(status().isOk());

        UUID plant1 = greenPlantOf(u1, "Фикус u1");
        UUID plant2 = greenPlantOf(u2, "Фикус u2");
        UUID plant3 = greenPlantOf(u3, "Фикус u3");
        acceptInvitation(u1, tournamentId, plant1);
        acceptInvitation(u2, tournamentId, plant2);
        acceptInvitation(u3, tournamentId, plant3);
        awaitTournamentStatus(tournamentId, "RUNNING");

        // первый раунд создан стартом (раздел 7): rounds с X-Total-Count
        UUID windowId = currentWindowId(organizer, tournamentId);
        UUID entry1 = entryOf(organizer, tournamentId, u1);
        UUID entry2 = entryOf(organizer, tournamentId, u2);
        UUID entry3 = entryOf(organizer, tournamentId, u3);

        // дельты (раздел 9): новый LIKE +1
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + entry2 + "/vote")
                .header(DEMO_HEADER, u1.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.score").value(1));
        // повтор LIKE не меняет счёт
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + entry2 + "/vote")
                .header(DEMO_HEADER, u1.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.score").value(1));
        // DISLIKE другого субъекта: 1 − 1 = 0
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + entry2 + "/vote")
                .header(DEMO_HEADER, u3.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"DISLIKE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.score").value(0));
        // u2 DISLIKE entry1: −1; u3 LIKE → DISLIKE (−2), затем удаление (+1): итог −1
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + entry1 + "/vote")
                .header(DEMO_HEADER, u2.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"DISLIKE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.score").value(-1));
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + entry1 + "/vote")
                .header(DEMO_HEADER, u3.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.score").value(0));
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + entry1 + "/vote")
                .header(DEMO_HEADER, u3.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"DISLIKE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.score").value(-2));
        mockMvc.perform(delete("/api/v1/windows/" + windowId + "/entries/" + entry1 + "/vote")
                .header(DEMO_HEADER, u3.toString()))
            .andExpect(status().isNoContent());
        // повторное удаление безопасно
        mockMvc.perform(delete("/api/v1/windows/" + windowId + "/entries/" + entry1 + "/vote")
                .header(DEMO_HEADER, u3.toString()))
            .andExpect(status().isNoContent());
        // my-vote: текущее значение / null
        mockMvc.perform(get("/api/v1/windows/" + windowId + "/entries/" + entry2 + "/my-vote")
                .header(DEMO_HEADER, u1.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.value").value("LIKE"));
        mockMvc.perform(get("/api/v1/windows/" + windowId + "/entries/" + entry1 + "/my-vote")
                .header(DEMO_HEADER, u3.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.value").doesNotExist());

        // права (раздел 2/9): гость 401, посторонний 404, организатор-не-участник 403,
        // самоголосование 403, entry вне окна 404, неизвестное окно 404, значение 400
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + entry2 + "/vote")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + entry2 + "/vote")
                .header(DEMO_HEADER, stranger.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + entry2 + "/vote")
                .header(DEMO_HEADER, organizer.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + entry1 + "/vote")
                .header(DEMO_HEADER, u1.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + UUID.randomUUID() + "/vote")
                .header(DEMO_HEADER, u1.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/windows/" + UUID.randomUUID() + "/entries/" + entry2 + "/vote")
                .header(DEMO_HEADER, u1.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + entry2 + "/vote")
                .header(DEMO_HEADER, u1.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"APPLAUSE\"}"))
            .andExpect(status().isBadRequest());

        // лидерборд открытого окна: score DESC (entry2 0, entry3 0, entry1 −1)
        mockMvc.perform(get("/api/v1/tournaments/" + tournamentId + "/leaderboard")
                .header(DEMO_HEADER, u1.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].score").value(0))
            .andExpect(jsonPath("$.items[2].score").value(-1));

        // закрытие по дедлайну окна (scheduler, 5 с): 3 участника, f=0.5 →
        // min(2, max(1, floor(1.5))) = 1 выбывший — худший entry1 (score −1)
        awaitRoundClosed(organizer, tournamentId, 1);

        mockMvc.perform(get("/api/v1/tournaments/" + tournamentId + "/entries")
                .header(DEMO_HEADER, u1.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.userId=='%s')].status").value(u1.toString(), "ELIMINATED"))
            .andExpect(jsonPath("$[?(@.userId=='%s')].status").value(u2.toString(), "ACTIVE"));

        // гибель и постоянный запрет (допущения 1–2): растение u1 DEAD,
        // повторная загрузка той же картинки — 409 IMAGE_RESTRICTED
        mockMvc.perform(get("/api/v1/plants/" + plant1)
                .header(DEMO_HEADER, u1.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lifeStatus").value("DEAD"));
        UUID sameImageAsset = uploadAs(u1, referenceBytes("green-8x8.png"));
        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, u1.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":\"%s\",\"title\":\"Повтор u1\"}".formatted(sameImageAsset)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IMAGE_RESTRICTED"));

        // голос в закрытом окне — 409; следующий раунд открыт, счёт с нуля
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + entry2 + "/vote")
                .header(DEMO_HEADER, u1.toString()) // выбывший голосовать может (допущение 9)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isConflict());
        UUID window2 = currentWindowId(organizer, tournamentId);
        assertThat(window2).isNotEqualTo(windowId);
        mockMvc.perform(get("/api/v1/tournaments/" + tournamentId + "/rounds")
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total-Count", "2"));

        // второй раунд: 2 участника, без голосов → 1 выживший = WINNER, FINISHED
        awaitTournamentStatus(tournamentId, "FINISHED");

        String results = mockMvc.perform(get("/api/v1/tournaments/" + tournamentId + "/results")
                .header(DEMO_HEADER, u1.toString()))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Total-Count"))
            .andReturn().getResponse().getContentAsString();
        UUID winnerEntry = UUID.fromString(JsonPath.read(results, "$.winnerEntryId"));
        UUID winnerUser = UUID.fromString(JsonPath.read(results,
            "$.items[?(@.entryId=='%s')].userId".formatted(winnerEntry)));
        assertThat(winnerUser).isIn(u2, u3);
        assertThat((Integer) JsonPath.read(results,
            "$.items[?(@.entryId=='%s')].eliminatedInRound".formatted(entry1))).isEqualTo(1);

        // победитель ALIVE, резерв освобождён — растение можно архивировать (204)
        UUID winnerPlant = winnerUser.equals(u2) ? plant2 : plant3;
        mockMvc.perform(get("/api/v1/plants/" + winnerPlant)
                .header(DEMO_HEADER, winnerUser.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lifeStatus").value("ALIVE"));
        mockMvc.perform(delete("/api/v1/plants/" + winnerPlant)
                .header(DEMO_HEADER, winnerUser.toString()))
            .andExpect(status().isNoContent());
    }

    // ---------- helpers (по образцу TournamentsApiIT) ----------

    private UUID createTournamentAsAdmin(String name, Instant deadline,
                                         long roundDurationSeconds, double fraction)
            throws Exception {
        String body = mockMvc.perform(post("/api/v1/tournaments")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"%s","description":"Описание","registrationDeadline":"%s",\
                    "roundDurationSeconds":%d,"eliminationFraction":%s,\
                    "minParticipants":2,"tagIds":[]}
                    """.formatted(name, deadline, roundDurationSeconds, fraction)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID currentWindowId(UUID viewer, UUID tournamentId) throws Exception {
        awaitRoundsPresent(viewer, tournamentId);
        String body = mockMvc.perform(get("/api/v1/tournaments/" + tournamentId + "/rounds")
                .header(DEMO_HEADER, viewer.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$[-1].id"));
    }

    private void awaitRoundsPresent(UUID viewer, UUID tournamentId) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> mockMvc.perform(
                    get("/api/v1/tournaments/" + tournamentId + "/rounds")
                        .header(DEMO_HEADER, viewer.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1")));
    }

    private void awaitRoundClosed(UUID viewer, UUID tournamentId, int sequence) {
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> mockMvc.perform(
                    get("/api/v1/tournaments/" + tournamentId + "/rounds")
                        .header(DEMO_HEADER, viewer.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[%d].status".formatted(sequence - 1)).value("CLOSED")));
    }

    private UUID entryOf(UUID viewer, UUID tournamentId, UUID userId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/tournaments/" + tournamentId + "/entries")
                .header(DEMO_HEADER, viewer.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<?> items = JsonPath.read(body, "$[?(@.userId=='%s')]".formatted(userId));
        return UUID.fromString((String) ((java.util.Map<String, Object>) items.get(0)).get("id"));
    }

    private void inviteAsAdmin(UUID tournamentId, UUID userId) throws Exception {
        mockMvc.perform(post("/api/v1/tournaments/" + tournamentId + "/invitations")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\"}".formatted(userId)))
            .andExpect(status().isCreated());
    }

    private void acceptInvitation(UUID user, UUID tournamentId, UUID plantId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/me/invitations")
                .header(DEMO_HEADER, user.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<?> items = JsonPath.read(body, "$[?(@.status=='INVITED')]");
        UUID invitationId = UUID.fromString(
            (String) ((java.util.Map<String, Object>) items.get(0)).get("id"));
        mockMvc.perform(post("/api/v1/invitations/" + invitationId + "/accept")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plantId)))
            .andExpect(status().isOk());
    }

    private UUID greenPlantOf(UUID user, String title) throws Exception {
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

- [ ] **Step 3: Запустить — красный**

```bash
./mvnw -q verify -Dit.test=VotingApiIT -DfailIfNoTests=false
```

Ожидание: unit-тесты PASS; `VotingApiIT` FAIL: все сценарии падают на `GET /api/v1/tournaments/{id}/rounds` — 404 (ручек rounds/leaderboard/vote нет). Это честный красный внешнего цикла.

- [ ] **Step 4: Commit**

```bash
git checkout -b feat/iteration-6-voting
git add docs/specs/2026-09-27-iteration-6-voting-design.md \
  src/main/java/com/plantarena/tournaments/application/port/out/PlantLifecycleGateway.java \
  src/test/java/com/plantarena/tournaments/VotingApiIT.java
git commit -m "feat(voting): порт PlantLifecycleGateway, красный приёмочный VotingApiIT"
```

---

### Task 2: Домен — голоса и дельты, агрегат `VotingWindow`

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/domain/VoteValue.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/VotingSubject.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/WindowStatus.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/ParticipantResult.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/WindowParticipant.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/Vote.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/VotingWindow.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/VotingWindowRepository.java`
- Test: `src/test/java/com/plantarena/tournaments/domain/VoteValueTest.java`
- Test: `src/test/java/com/plantarena/tournaments/domain/VotingWindowTest.java`

**Interfaces:**
- Consumes: `EliminationAlgorithm` создаётся в Task 3 — поэтому `VotingWindow.close` добавляется в Task 3; в этом таске агрегат без close.
- Produces (для Tasks 3–7): `VoteValue { LIKE, DISLIKE; long contribution(); static long transitionDelta(VoteValue previous, VoteValue next); }`; `VotingSubject.user(UUID) → subjectKey()="USER:<uuid>", isUser(UUID), userId()`; `VotingWindow.open(UUID tournamentId, int sequence, List<ParticipantSeed> seeds, Instant opensAt, Instant closesAt, Instant now)`, `castVote(VotingSubject, UUID entryId, VoteValue, Instant now) → long score`, `removeVote(...) → long score`, `myVote(String subjectKey, UUID entryId) → VoteValue|null`, `isAcceptingVotes(Instant)`, `hasEntry(UUID)`, `userIdOfEntry(UUID)`, `participants()`, `votesOf(UUID entryId)`; `record ParticipantSeed(UUID entryId, UUID userId, Instant joinedAt)`; порт `VotingWindowRepository` (методы — в коде ниже).

- [ ] **Step 1: Красные доменные тесты**

`src/test/java/com/plantarena/tournaments/domain/VoteValueTest.java`:

```java
package com.plantarena.tournaments.domain;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Дельты голосов (раздел 9): чистая функция переходов, исчерпывающие случаи. */
@DisplayName("Дельта счёта при переходе previous → next (null — голоса не было)")
class VoteValueTest {

    static Stream<Arguments> transitions() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.arguments(null, VoteValue.LIKE, 1L),
            Arguments.arguments(null, VoteValue.DISLIKE, -1L),
            Arguments.arguments(VoteValue.LIKE, VoteValue.LIKE, 0L),
            Arguments.arguments(VoteValue.DISLIKE, VoteValue.DISLIKE, 0L),
            Arguments.arguments(VoteValue.LIKE, VoteValue.DISLIKE, -2L),
            Arguments.arguments(VoteValue.DISLIKE, VoteValue.LIKE, 2L),
            Arguments.arguments(VoteValue.LIKE, null, -1L),
            Arguments.arguments(VoteValue.DISLIKE, null, 1L),
            Arguments.arguments(null, null, 0L));
    }

    @ParameterizedTest(name = "{0} → {1} = {2}")
    @MethodSource("transitions")
    void дельта_перехода(VoteValue previous, VoteValue next, long expected) {
        assertThat(VoteValue.transitionDelta(previous, next)).isEqualTo(expected);
    }
}
```

`src/test/java/com/plantarena/tournaments/domain/VotingWindowTest.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Инварианты окна голосования (разделы 7, 9, 12.1). */
@DisplayName("Окно голосования: состав, дельты, самоголосование, полуоткрытый интервал")
class VotingWindowTest {

    private static final UUID TOURNAMENT = UUID.randomUUID();
    private static final UUID USER_1 = UUID.randomUUID();
    private static final UUID USER_2 = UUID.randomUUID();
    private static final UUID USER_3 = UUID.randomUUID();
    private static final UUID ENTRY_1 = UUID.randomUUID();
    private static final UUID ENTRY_2 = UUID.randomUUID();
    private static final UUID ENTRY_3 = UUID.randomUUID();
    private static final Instant OPENS = Instant.parse("2026-09-27T10:00:00Z");
    private static final Instant CLOSES = OPENS.plusSeconds(60);

    @Test
    @DisplayName("окно private-турнира открывается минимум с двумя участниками")
    void окно_минимум_с_двумя() {
        List<VotingWindow.ParticipantSeed> one = List.of(seed(ENTRY_1, USER_1));
        assertThatThrownBy(() ->
            VotingWindow.open(TOURNAMENT, 1, one, OPENS, CLOSES, OPENS))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("новый LIKE даёт +1, второй субъект LIKE даёт +1")
    void новые_голоса() {
        VotingWindow window = window3();
        assertThat(window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.LIKE, OPENS))
            .isEqualTo(1L);
        assertThat(window.castVote(VotingSubject.user(USER_3), ENTRY_2, VoteValue.LIKE, OPENS))
            .isEqualTo(2L);
    }

    @Test
    @DisplayName("повтор LIKE после LIKE не меняет счёт")
    void повтор_того_же_значения() {
        VotingWindow window = window3();
        window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.LIKE, OPENS);
        assertThat(window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.LIKE, OPENS))
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("LIKE → DISLIKE меняет счёт на −2")
    void смена_знака() {
        VotingWindow window = window3();
        window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.LIKE, OPENS);
        assertThat(window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.DISLIKE, OPENS))
            .isEqualTo(-1L);
    }

    @Test
    @DisplayName("удаление голоса компенсирует вклад; повторное удаление безопасно")
    void удаление_компенсирует() {
        VotingWindow window = window3();
        window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.DISLIKE, OPENS);
        assertThat(window.removeVote(VotingSubject.user(USER_1), ENTRY_2, OPENS)).isZero();
        assertThat(window.removeVote(VotingSubject.user(USER_1), ENTRY_2, OPENS)).isZero();
    }

    @Test
    @DisplayName("score равен сумме текущих голосов окна и entry (инвариант)")
    void score_равен_сумме_голосов() {
        VotingWindow window = window3();
        window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.LIKE, OPENS);
        window.castVote(VotingSubject.user(USER_2), ENTRY_3, VoteValue.DISLIKE, OPENS);
        window.castVote(VotingSubject.user(USER_3), ENTRY_2, VoteValue.DISLIKE, OPENS);
        window.castVote(VotingSubject.user(USER_3), ENTRY_2, VoteValue.LIKE, OPENS);
        window.removeVote(VotingSubject.user(USER_1), ENTRY_2, OPENS);
        long sum = window.votesOf(ENTRY_2).stream()
            .mapToLong(vote -> vote.value().contribution()).sum();
        assertThat(window.scoreOf(ENTRY_2)).isEqualTo(sum).isEqualTo(0L);
    }

    @Test
    @DisplayName("самоголосование запрещено для идентифицированного пользователя (допущение 6)")
    void самоголосование_запрещено() {
        VotingWindow window = window3();
        assertThatThrownBy(() ->
            window.castVote(VotingSubject.user(USER_1), ENTRY_1, VoteValue.LIKE, OPENS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Самоголосование");
    }

    @Test
    @DisplayName("голос за entry вне окна и за чужое окно — ошибка состояния")
    void entry_вне_окна() {
        VotingWindow window = window3();
        assertThatThrownBy(() -> window.castVote(VotingSubject.user(USER_1),
            UUID.randomUUID(), VoteValue.LIKE, OPENS))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("интервал [opensAt, closesAt): в closesAt новый голос уже запрещён")
    void полуоткрытый_интервал() {
        VotingWindow window = window3();
        assertThat(window.isAcceptingVotes(CLOSES.minusNanos(1))).isTrue();
        assertThat(window.isAcceptingVotes(CLOSES)).isFalse();
        assertThatThrownBy(() ->
            window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.LIKE, CLOSES))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Дедлайн");
    }

    @Test
    @DisplayName("myVote возвращает текущее значение или null")
    void my_vote() {
        VotingWindow window = window3();
        String subject = VotingSubject.user(USER_1).subjectKey();
        assertThat(window.myVote(subject, ENTRY_2)).isNull();
        window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.LIKE, OPENS);
        assertThat(window.myVote(subject, ENTRY_2)).isEqualTo(VoteValue.LIKE);
    }

    private VotingWindow window3() {
        return VotingWindow.open(TOURNAMENT, 1, List.of(
            seed(ENTRY_1, USER_1), seed(ENTRY_2, USER_2), seed(ENTRY_3, USER_3)),
            OPENS, CLOSES, OPENS);
    }

    private VotingWindow.ParticipantSeed seed(UUID entryId, UUID userId) {
        return new VotingWindow.ParticipantSeed(entryId, userId,
            Instant.parse("2026-09-27T09:00:00Z"));
    }
}
```

- [ ] **Step 2: Запустить — красный**

```bash
./mvnw -q test -Dtest='VoteValueTest,VotingWindowTest'
```

Ожидание: FAIL (компиляция): `cannot find symbol: class VoteValue` / `class VotingWindow`.

- [ ] **Step 3: Минимальная реализация домена**

`src/main/java/com/plantarena/tournaments/domain/VoteValue.java`:

```java
package com.plantarena.tournaments.domain;

/**
 * Значение голоса (раздел 9): LIKE/DISLIKE, числовой вклад +1/−1. Дельты
 * переходов — чистая доменная функция: previous == null — голоса не было,
 * next == null — удаление.
 */
public enum VoteValue {
    LIKE, DISLIKE;

    /** Числовый вклад текущего голоса в счёт. */
    public long contribution() {
        return this == LIKE ? 1L : -1L;
    }

    /**
     * Дельта счёта при переходе previous → next (раздел 9): новый голос —
     * вклад, смена знака — ±2, повтор того же значения — 0, удаление —
     * компенсация вклада.
     */
    public static long transitionDelta(VoteValue previous, VoteValue next) {
        if (previous == next) {
            return 0L;
        }
        if (previous == null) {
            return next.contribution();
        }
        if (next == null) {
            return -previous.contribution();
        }
        return next.contribution() - previous.contribution();
    }
}
```

`src/main/java/com/plantarena/tournaments/domain/VotingSubject.java`:

```java
package com.plantarena.tournaments.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Субъект голосования (раздел 9): USER(userId) — итерация 6; GUEST(токен
 * гостевой сессии) — итерация 8 (глобальные окна). subjectKey — стабильный
 * ключ уникальности (окно, entry, субъект).
 */
public final class VotingSubject {

    private final UUID userId;

    private VotingSubject(UUID userId) {
        this.userId = Objects.requireNonNull(userId, "userId");
    }

    public static VotingSubject user(UUID userId) {
        return new VotingSubject(userId);
    }

    /** Ключ уникальности голоса (хранится в БД как VARCHAR). */
    public String subjectKey() {
        return "USER:" + userId;
    }

    /** Самоголосование (допущение 6): субъект — владелец участия? */
    public boolean isUser(UUID candidate) {
        return userId.equals(candidate);
    }

    public UUID userId() {
        return userId;
    }
}
```

`src/main/java/com/plantarena/tournaments/domain/WindowStatus.java`:

```java
package com.plantarena.tournaments.domain;

/** Статус окна (раздел 11): OPEN/CLOSED; повторное закрытие не меняет результатов. */
public enum WindowStatus {
    OPEN, CLOSED
}
```

`src/main/java/com/plantarena/tournaments/domain/ParticipantResult.java`:

```java
package com.plantarena.tournaments.domain;

/**
 * Итог участника окна (раздел 11): ACTIVE — идёт голосование; SURVIVED —
 * пережил окно (следующий раунд); ELIMINATED — выбыл; WINNER — победил.
 * PROMOTED (глобальная квалификация) — итерация 7.
 */
public enum ParticipantResult {
    ACTIVE, SURVIVED, ELIMINATED, WINNER
}
```

`src/main/java/com/plantarena/tournaments/domain/WindowParticipant.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Участник окна (раздел 9): участие entry в конкретном окне — счёт и итог.
 * Изменяется только агрегатом VotingWindow (package-private операции).
 * userId денормализован из entry: запрет самоголосования — правило окна.
 */
public final class WindowParticipant {

    private final UUID id;
    private final UUID entryId;
    private final UUID userId;
    private long score;
    private ParticipantResult result;
    private final Instant joinedAt;

    private WindowParticipant(UUID id, UUID entryId, UUID userId, long score,
                              ParticipantResult result, Instant joinedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.entryId = Objects.requireNonNull(entryId, "entryId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.result = Objects.requireNonNull(result, "result");
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
        this.score = score;
    }

    static WindowParticipant newParticipant(UUID entryId, UUID userId, Instant joinedAt) {
        return new WindowParticipant(UUID.randomUUID(), entryId, userId, 0L,
            ParticipantResult.ACTIVE, joinedAt);
    }

    /** Восстановление из хранилища (использует только persistence-адаптер). */
    public static WindowParticipant restore(UUID id, UUID entryId, UUID userId, long score,
                                            ParticipantResult result, Instant joinedAt) {
        return new WindowParticipant(id, entryId, userId, score, result, joinedAt);
    }

    void applyDelta(long delta) {
        score += delta;
    }

    void eliminate() {
        requireActive();
        result = ParticipantResult.ELIMINATED;
    }

    void survive() {
        requireActive();
        result = ParticipantResult.SURVIVED;
    }

    void declareWinner() {
        requireActive();
        result = ParticipantResult.WINNER;
    }

    private void requireActive() {
        if (result != ParticipantResult.ACTIVE) {
            throw new IllegalStateException("Итог участника уже зафиксирован: " + result);
        }
    }

    public UUID id() {
        return id;
    }

    public UUID entryId() {
        return entryId;
    }

    public UUID userId() {
        return userId;
    }

    public long score() {
        return score;
    }

    public ParticipantResult result() {
        return result;
    }

    public Instant joinedAt() {
        return joinedAt;
    }
}
```

`src/main/java/com/plantarena/tournaments/domain/Vote.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Голос (раздел 9): LIKE/DISLIKE субъекта за участника окна. Один голос
 * одного субъекта за участника в одном окне (уникальность — ключи агрегата
 * и UNIQUE в БД); значение можно менять до закрытия окна.
 */
public final class Vote {

    private final UUID id;
    private final UUID entryId;
    private final String subjectKey;
    private VoteValue value;
    private final Instant createdAt;
    private Instant updatedAt;

    private Vote(UUID id, UUID entryId, String subjectKey, VoteValue value,
                 Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.entryId = Objects.requireNonNull(entryId, "entryId");
        this.subjectKey = Objects.requireNonNull(subjectKey, "subjectKey");
        this.value = Objects.requireNonNull(value, "value");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    static Vote newVote(UUID entryId, String subjectKey, VoteValue value, Instant now) {
        return new Vote(UUID.randomUUID(), entryId, subjectKey, value, now, now);
    }

    /** Восстановление из хранилища (использует только persistence-адаптер). */
    public static Vote restore(UUID id, UUID entryId, String subjectKey, VoteValue value,
                               Instant createdAt, Instant updatedAt) {
        return new Vote(id, entryId, subjectKey, value, createdAt, updatedAt);
    }

    void update(VoteValue value, Instant now) {
        this.value = value;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID entryId() {
        return entryId;
    }

    public String subjectKey() {
        return subjectKey;
    }

    public VoteValue value() {
        return value;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
```

`src/main/java/com/plantarena/tournaments/domain/VotingWindow.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат tournaments (разделы 7, 9, 12.1): окно голосования с зафиксированным
 * составом. Инварианты: интервал [opensAt, closesAt); score участника = сумма
 * текущих голосов за него; один голос субъекта за участника; самоголосование
 * запрещено; повторное закрытие не меняет результатов. Изменяется только
 * через методы корня; время приходит аргументом. Блокировка (FOR UPDATE) —
 * ответственность хранилища, сериализует голоса и закрытие (раздел 12.1).
 */
public final class VotingWindow {

    private final UUID id;
    private final UUID tournamentId;
    private final int sequence;
    private WindowStatus status;
    private final Instant opensAt;
    private final Instant closesAt;
    private final Map<UUID, WindowParticipant> participantsByEntry = new LinkedHashMap<>();
    private final Map<String, Vote> votesByKey = new LinkedHashMap<>();
    private final Instant createdAt;
    private long version;

    private VotingWindow(UUID id, UUID tournamentId, int sequence, WindowStatus status,
                         Instant opensAt, Instant closesAt, Instant createdAt, long version) {
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

    /**
     * Открыть окно раунда: старт турнира (sequence 1) или закрытие предыдущего
     * (sequence + 1, выжившие, счёт с нуля). Состав фиксируется и не меняется.
     */
    public static VotingWindow open(UUID tournamentId, int sequence,
                                     List<ParticipantSeed> seeds, Instant opensAt,
                                     Instant closesAt, Instant now) {
        Objects.requireNonNull(seeds, "seeds");
        if (seeds.size() < 2) {
            throw new IllegalArgumentException(
                "Окно private-турнира открывается минимум с двумя участниками");
        }
        VotingWindow window = new VotingWindow(UUID.randomUUID(), tournamentId, sequence,
            WindowStatus.OPEN, opensAt, closesAt, now, 0L);
        for (ParticipantSeed seed : seeds) {
            if (window.participantsByEntry.containsKey(seed.entryId())) {
                throw new IllegalArgumentException("Дубликат участника в окне: " + seed.entryId());
            }
            window.participantsByEntry.put(seed.entryId(),
                WindowParticipant.newParticipant(seed.entryId(), seed.userId(), seed.joinedAt()));
        }
        return window;
    }

    /** Восстановление из хранилища (использует только persistence-адаптер). */
    public static VotingWindow restore(UUID id, UUID tournamentId, int sequence,
                                       WindowStatus status, Instant opensAt, Instant closesAt,
                                       Instant createdAt, long version,
                                       Collection<WindowParticipant> participants,
                                       Collection<Vote> votes) {
        VotingWindow window = new VotingWindow(id, tournamentId, sequence, status,
            opensAt, closesAt, createdAt, version);
        participants.forEach(participant ->
            window.participantsByEntry.put(participant.entryId(), participant));
        votes.forEach(vote ->
            window.votesByKey.put(voteKey(vote.subjectKey(), vote.entryId()), vote));
        return window;
    }

    /**
     * Установить голос субъекта (PUT, раздел 9); возвращает новый score.
     * Повтор того же значения не меняет счёт, смена — ±2.
     */
    public long castVote(VotingSubject subject, UUID entryId, VoteValue value, Instant now) {
        requireAcceptingVotes(now);
        WindowParticipant participant = participant(entryId);
        if (subject.isUser(participant.userId())) {
            throw new IllegalStateException("Самоголосование запрещено (допущение 6)");
        }
        Vote existing = votesByKey.get(voteKey(subject.subjectKey(), entryId));
        long delta = VoteValue.transitionDelta(existing == null ? null : existing.value(), value);
        if (existing == null) {
            votesByKey.put(voteKey(subject.subjectKey(), entryId),
                Vote.newVote(entryId, subject.subjectKey(), value, now));
        } else {
            existing.update(value, now);
        }
        participant.applyDelta(delta);
        return participant.score();
    }

    /** Удалить голос субъекта (DELETE, раздел 9); идемпотентно; возвращает score. */
    public long removeVote(VotingSubject subject, UUID entryId, Instant now) {
        requireAcceptingVotes(now);
        WindowParticipant participant = participant(entryId);
        Vote existing = votesByKey.remove(voteKey(subject.subjectKey(), entryId));
        if (existing != null) {
            participant.applyDelta(-existing.value().contribution());
        }
        return participant.score();
    }

    /** Текущий голос субъекта за участника (null — голоса нет). */
    public VoteValue myVote(String subjectKey, UUID entryId) {
        Vote vote = votesByKey.get(voteKey(subjectKey, entryId));
        return vote == null ? null : vote.value();
    }

    /** Принимает ли окно новые голоса/удаления: OPEN и now < closesAt. */
    public boolean isAcceptingVotes(Instant now) {
        return status == WindowStatus.OPEN && now.isBefore(closesAt);
    }

    public boolean hasEntry(UUID entryId) {
        return participantsByEntry.containsKey(entryId);
    }

    /** Владелец участия (проверка самоголосования; денормализация из entry). */
    public UUID userIdOfEntry(UUID entryId) {
        return participant(entryId).userId();
    }

    /** Счёт участника (инвариант: сумма текущих голосов). */
    public long scoreOf(UUID entryId) {
        return participant(entryId).score();
    }

    public Collection<WindowParticipant> participants() {
        return List.copyOf(participantsByEntry.values());
    }

    /** Голоса за участника окна (persistence-маппинг, проверка инварианта). */
    public List<Vote> votesOf(UUID entryId) {
        return votesByKey.values().stream()
            .filter(vote -> vote.entryId().equals(entryId)).toList();
    }

    private void requireAcceptingVotes(Instant now) {
        if (status != WindowStatus.OPEN) {
            throw new IllegalStateException("Голосование в закрытом окне запрещено: " + id);
        }
        if (!now.isBefore(closesAt)) {
            throw new IllegalStateException("Дедлайн окна истёк: " + closesAt);
        }
    }

    private WindowParticipant participant(UUID entryId) {
        WindowParticipant participant = participantsByEntry.get(entryId);
        if (participant == null) {
            throw new IllegalStateException("Участие не входит в окно: " + entryId);
        }
        return participant;
    }

    private static String voteKey(String subjectKey, UUID entryId) {
        return subjectKey + ":" + entryId;
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

    public WindowStatus status() {
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

    /** Состав нового окна: entry + владелец + joinedAt (из TournamentEntry). */
    public record ParticipantSeed(UUID entryId, UUID userId, Instant joinedAt) {
    }
}
```

`src/main/java/com/plantarena/tournaments/domain/VotingWindowRepository.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Порт репозитория агрегата VotingWindow (разделы 9, 12.1). */
public interface VotingWindowRepository {

    VotingWindow save(VotingWindow window);

    Optional<VotingWindow> findById(UUID id);

    /**
     * Загрузка с блокировкой окна (раздел 12.1): SELECT ... FOR UPDATE в
     * JPA-адаптере; сериализует голоса и закрытие одного окна. Проверку
     * времени вызывающий выполняет после получения блокировки.
     */
    Optional<VotingWindow> findByIdForUpdate(UUID id);

    /** Просроченные OPEN-окна (closesAt <= now) для scheduler'а закрытия. */
    List<UUID> findDueForClose(Instant now, int limit);

    List<VotingWindow> findByTournamentId(UUID tournamentId, int offset, int size);

    long countByTournamentId(UUID tournamentId);

    /** Последнее окно турнира (максимум sequence) — лидерборд по умолчанию. */
    Optional<VotingWindow> findLatestByTournamentId(UUID tournamentId);

    /** Все окна турнира (итоги: раунд выбывания каждого entry; турнир конечен). */
    List<VotingWindow> findAllByTournamentId(UUID tournamentId);
}
```

- [ ] **Step 4: Запустить — зелёный**

```bash
./mvnw -q test -Dtest='VoteValueTest,VotingWindowTest'
```

Ожидание: PASS (10 тестов VoteValueTest параметризованных + 10 VotingWindowTest).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/plantarena/tournaments/domain \
  src/test/java/com/plantarena/tournaments/domain/VoteValueTest.java \
  src/test/java/com/plantarena/tournaments/domain/VotingWindowTest.java
git commit -m "feat(voting): домен — голоса, дельты, субъект, агрегат VotingWindow (голосование)"
```

---

### Task 3: Домен — `RoundElimination`, `ParticipantRanking`, закрытие окна, финал турнира

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/domain/EliminationAlgorithm.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/RoundElimination.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/EliminationAlgorithms.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/ParticipantRanking.java`
- Modify: `src/main/java/com/plantarena/tournaments/domain/VotingWindow.java` (метод `close`)
- Modify: `src/main/java/com/plantarena/tournaments/domain/Tournament.java` (метод `finish`)
- Modify: `src/main/java/com/plantarena/tournaments/domain/TournamentEntry.java` (`eliminate`/`declareWinner`, status становится mutable)
- Modify: `src/main/java/com/plantarena/tournaments/domain/TournamentEntryRepository.java` (`findById`)
- Test: `src/test/java/com/plantarena/tournaments/domain/RoundEliminationTest.java`
- Test: `src/test/java/com/plantarena/tournaments/domain/ParticipantRankingTest.java`
- Test: `src/test/java/com/plantarena/tournaments/domain/VotingWindowTest.java` (добавить тесты close)
- Test: `src/test/java/com/plantarena/tournaments/domain/TournamentTest.java` (добавить finish)
- Test: `src/test/java/com/plantarena/tournaments/domain/TournamentEntryTest.java` (добавить переходы)

**Interfaces:**
- Consumes: `VotingWindow` из Task 2.
- Produces (для Tasks 5–7): `EliminationAlgorithm { int eliminatedCount(int participants, double eliminationFraction); }`, `EliminationAlgorithms.forKind(EliminationAlgorithmKind)`, `ParticipantRanking.rank(Collection<WindowParticipant>) → List` (от лучшего к худшему), `VotingWindow.close(Instant now, EliminationAlgorithm, double fraction) → CloseOutcome(List<UUID> eliminatedEntryIds, List<UUID> survivedEntryIds, UUID winnerEntryId)` (winnerEntryId != null ⇔ survivedEntryIds пуст), `Tournament.finish(Instant now)`, `TournamentEntry.eliminate()/declareWinner()`, `TournamentEntryRepository.findById(UUID) → Optional<TournamentEntry>`.

- [ ] **Step 1: Красные тесты алгоритма и рейтинга**

`src/test/java/com/plantarena/tournaments/domain/RoundEliminationTest.java`:

```java
package com.plantarena.tournaments.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ROUND_ELIMINATION (раздел 7): min(n − 1, max(1, floor(n · f))). */
@DisplayName("Число выбывающих из n участников при доле f")
class RoundEliminationTest {

    private final RoundElimination algorithm = new RoundElimination();

    @ParameterizedTest(name = "n={0}, f={1} → {2}")
    @CsvSource({
        "2, 0.5, 1",   // min(1, max(1, 1))
        "3, 0.5, 1",   // floor(1.5) = 1
        "4, 0.5, 2",   // floor(2.0) = 2
        "5, 0.9, 4",   // min(4, max(1, 4)) — не выбывает весь состав
        "10, 0.1, 1",  // floor(1.0) = 1
        "3, 0.34, 1",  // floor(1.02) = 1
        "7, 0.5, 3"    // floor(3.5) = 3
    })
    void число_выбывающих(int n, double fraction, int expected) {
        assertThat(algorithm.eliminatedCount(n, fraction)).isEqualTo(expected);
    }

    @Test
    @DisplayName("n < 2 и доля вне (0, 1) — ошибки аргументов")
    void некорректные_аргументы() {
        assertThatThrownBy(() -> algorithm.eliminatedCount(1, 0.5))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> algorithm.eliminatedCount(0, 0.5))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> algorithm.eliminatedCount(3, 0.0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> algorithm.eliminatedCount(3, 1.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("фабрика выдаёт RoundElimination для ROUND_ELIMINATION")
    void фабрика() {
        assertThat(EliminationAlgorithms.forKind(EliminationAlgorithmKind.ROUND_ELIMINATION))
            .isInstanceOf(RoundElimination.class);
    }
}
```

`src/test/java/com/plantarena/tournaments/domain/ParticipantRankingTest.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Tie-break (допущение 8): score DESC, joinedAt ASC, entryId ASC. */
@DisplayName("Итоговый рейтинг окна: детерминированный порядок")
class ParticipantRankingTest {

    @Test
    @DisplayName("score DESC: худший — последний")
    void по_счёту() {
        WindowParticipant best = participant(10L, "2026-09-27T10:00:02Z", entry(7));
        WindowParticipant middle = participant(0L, "2026-09-27T10:00:00Z", entry(8));
        WindowParticipant worst = participant(-1L, "2026-09-27T10:00:01Z", entry(9));
        assertThat(ParticipantRanking.rank(List.of(worst, best, middle)))
            .containsExactly(best, middle, worst);
    }

    @Test
    @DisplayName("равный счёт → joinedAt ASC")
    void равный_счёт_по_времени() {
        WindowParticipant earlier = participant(5L, "2026-09-27T10:00:00Z", entry(7));
        WindowParticipant later = participant(5L, "2026-09-27T10:00:01Z", entry(8));
        assertThat(ParticipantRanking.rank(List.of(later, earlier)))
            .containsExactly(earlier, later);
    }

    @Test
    @DisplayName("равный счёт и время → entryId ASC; без голосов — тот же порядок")
    void полный_тай_брейк() {
        WindowParticipant low = participant(0L, "2026-09-27T10:00:00Z", entry(1));
        WindowParticipant high = participant(0L, "2026-09-27T10:00:00Z", entry(2));
        assertThat(ParticipantRanking.rank(List.of(high, low))).containsExactly(low, high);
    }

    private UUID entry(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-00000000000" + suffix);
    }

    private WindowParticipant participant(long score, String joinedAt, UUID entryId) {
        WindowParticipant participant = WindowParticipant.restore(UUID.randomUUID(), entryId,
            UUID.randomUUID(), score, ParticipantResult.ACTIVE, Instant.parse(joinedAt));
        return participant;
    }
}
```

- [ ] **Step 2: Красные тесты close/final в `VotingWindowTest`, `TournamentTest`, `TournamentEntryTest`**

Добавить в `VotingWindowTest`:

```java
    @Test
    @DisplayName("закрытие до closesAt невозможно")
    void закрытие_до_дедлайна() {
        VotingWindow window = window3();
        assertThatThrownBy(() -> window.close(CLOSES.minusSeconds(1),
            new RoundElimination(), 0.5))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("открыто");
    }

    @Test
    @DisplayName("закрытие: 3 участника, f=0.5 → 1 худший ELIMINATED, 2 SURVIVED")
    void закрытие_с_выбыванием() {
        VotingWindow window = window3();
        window.castVote(VotingSubject.user(USER_1), ENTRY_2, VoteValue.LIKE, OPENS);
        window.castVote(VotingSubject.user(USER_2), ENTRY_1, VoteValue.DISLIKE, OPENS);
        // счёт: entry2 +1, entry3 0, entry1 −1 → выбывает entry1

        VotingWindow.CloseOutcome outcome = window.close(CLOSES, new RoundElimination(), 0.5);

        assertThat(outcome.eliminatedEntryIds()).containsExactly(ENTRY_1);
        assertThat(outcome.survivedEntryIds()).containsExactly(ENTRY_2, ENTRY_3);
        assertThat(outcome.winnerEntryId()).isNull();
        assertThat(window.status()).isEqualTo(WindowStatus.CLOSED);
        assertThat(window.scoreOf(ENTRY_1)).isEqualTo(-1L); // итоги не переписываются
    }

    @Test
    @DisplayName("закрытие при одном выжившем: WINNER, окно с одним участником не создаётся")
    void закрытие_с_победителем() {
        VotingWindow window = VotingWindow.open(TOURNAMENT, 2, List.of(
            seed(ENTRY_2, USER_2), seed(ENTRY_3, USER_3)), OPENS, CLOSES, OPENS);
        window.castVote(VotingSubject.user(USER_2), ENTRY_3, VoteValue.LIKE, OPENS);
        // счёт: entry3 +1, entry2 0 → выбывает entry2 (f=0.5 → 1)

        VotingWindow.CloseOutcome outcome = window.close(CLOSES, new RoundElimination(), 0.5);

        assertThat(outcome.eliminatedEntryIds()).containsExactly(ENTRY_2);
        assertThat(outcome.survivedEntryIds()).isEmpty();
        assertThat(outcome.winnerEntryId()).isEqualTo(ENTRY_3);
    }

    @Test
    @DisplayName("повторное закрытие не меняет результатов (ошибка состояния; идемпотентность — use case)")
    void повторное_закрытие() {
        VotingWindow window = window3();
        window.close(CLOSES, new RoundElimination(), 0.5);
        assertThatThrownBy(() -> window.close(CLOSES.plusSeconds(1), new RoundElimination(), 0.5))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("уже закрыто");
    }

    @Test
    @DisplayName("ничья решается детерминированно: score DESC, joinedAt ASC, entryId ASC")
    void ничья_при_закрытии() {
        // все score 0, joinedAt равны → выбывает больший entryId
        VotingWindow window = window3();
        VotingWindow.CloseOutcome outcome = window.close(CLOSES, new RoundElimination(), 0.5);
        assertThat(outcome.eliminatedEntryIds()).containsExactly(ENTRY_3);
    }
```

Добавить в `TournamentTest` (существующий класс, стиль — по образцу тестов `start`):

```java
    @Test
    @DisplayName("finish: RUNNING → FINISHED (победитель определён, раздел 7)")
    void finish_из_running() {
        Tournament tournament = runningTournament(); // существующий хелпер или собрать:
        // createDraft → openRegistration(now) → start(now после дедлайна, readyCount)
        tournament.finish(NOW_AFTER_DEADLINE);
        assertThat(tournament.status()).isEqualTo(TournamentStatus.FINISHED);
    }

    @Test
    @DisplayName("finish из не-RUNNING — ошибка состояния")
    void finish_не_из_running() {
        Tournament draft = Tournament.createDraft(CREATOR, "Черновик", null,
            DEADLINE.plusSeconds(600), Duration.ofSeconds(3600), 0.5, 2, Set.of(), NOW);
        assertThatThrownBy(() -> draft.finish(NOW))
            .isInstanceOf(IllegalStateException.class);
    }
```

Добавить в `TournamentEntryTest`:

```java
    @Test
    @DisplayName("выбывание: ACTIVE → ELIMINATED; повтор — ошибка (итог не переписывается)")
    void выбывание() {
        TournamentEntry entry = TournamentEntry.admit(TOURNAMENT, USER, PLANT,
            RESERVATION, NOW);
        entry.eliminate();
        assertThat(entry.status()).isEqualTo(EntryStatus.ELIMINATED);
        assertThatThrownBy(entry::eliminate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("победа: ACTIVE → WINNER; повтор — ошибка")
    void победа() {
        TournamentEntry entry = TournamentEntry.admit(TOURNAMENT, USER, PLANT,
            RESERVATION, NOW);
        entry.declareWinner();
        assertThat(entry.status()).isEqualTo(EntryStatus.WINNER);
        assertThatThrownBy(entry::declareWinner).isInstanceOf(IllegalStateException.class);
    }
```

- [ ] **Step 3: Запустить — красный**

```bash
./mvnw -q test -Dtest='RoundEliminationTest,ParticipantRankingTest,VotingWindowTest,TournamentTest,TournamentEntryTest'
```

Ожидание: FAIL (компиляция): `cannot find symbol: class RoundElimination` / `method close` / `method finish` / `method eliminate`.

- [ ] **Step 4: Реализация**

`src/main/java/com/plantarena/tournaments/domain/EliminationAlgorithm.java`:

```java
package com.plantarena.tournaments.domain;

/** Стратегия выбывания (раздел 5): сколько худших выбывает из окна. */
public interface EliminationAlgorithm {

    /**
     * Число выбывающих из n участников.
     *
     * @throws IllegalArgumentException n &lt; 2 или доля вне (0, 1)
     */
    int eliminatedCount(int participants, double eliminationFraction);
}
```

`src/main/java/com/plantarena/tournaments/domain/RoundElimination.java`:

```java
package com.plantarena.tournaments.domain;

/**
 * ROUND_ELIMINATION (раздел 7): в каждом раунде из n &gt; 1 выбывает
 * min(n − 1, max(1, floor(n · eliminationFraction))) худших.
 */
public final class RoundElimination implements EliminationAlgorithm {

    @Override
    public int eliminatedCount(int participants, double eliminationFraction) {
        if (participants < 2) {
            throw new IllegalArgumentException("Выбывание считается при n >= 2: " + participants);
        }
        if (eliminationFraction <= 0d || eliminationFraction >= 1d) {
            throw new IllegalArgumentException("Доля выбывания в (0, 1): " + eliminationFraction);
        }
        return Math.min(participants - 1,
            Math.max(1, (int) Math.floor(participants * eliminationFraction)));
    }
}
```

`src/main/java/com/plantarena/tournaments/domain/EliminationAlgorithms.java`:

```java
package com.plantarena.tournaments.domain;

/** Фабрика стратегий выбывания по алгоритму турнира (раздел 7). */
public final class EliminationAlgorithms {

    private EliminationAlgorithms() {
    }

    public static EliminationAlgorithm forKind(EliminationAlgorithmKind kind) {
        return switch (kind) {
            case ROUND_ELIMINATION -> new RoundElimination();
        };
    }
}
```

`src/main/java/com/plantarena/tournaments/domain/ParticipantRanking.java`:

```java
package com.plantarena.tournaments.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Итоговый рейтинг окна (допущение 8): score DESC, joinedAt ASC, entryId ASC.
 * Детерминирован; при отсутствии голосов действует тот же порядок.
 */
public final class ParticipantRanking {

    private static final Comparator<WindowParticipant> ORDER =
        Comparator.comparingLong(WindowParticipant::score).reversed()
            .thenComparing(WindowParticipant::joinedAt)
            .thenComparing(WindowParticipant::entryId);

    private ParticipantRanking() {
    }

    /** От лучшего к худшему. */
    public static List<WindowParticipant> rank(Collection<WindowParticipant> participants) {
        return participants.stream().sorted(ORDER).toList();
    }
}
```

`VotingWindow` — добавить метод `close` (после `removeVote`) и record `CloseOutcome` (внутри класса, рядом с `ParticipantSeed`):

```java
    /**
     * Закрытие окна (разделы 7, 12.3): фиксирует итоговый рейтинг и результаты
     * участников. Повторный вызов для CLOSED — ошибка состояния; идемпотентность
     * повтора (windowId, sequence) — на уровне use case по статусу. Если после
     * выбывания остаётся один — он WINNER (турнир FINISHED решает use case).
     */
    public CloseOutcome close(Instant now, EliminationAlgorithm algorithm,
                              double eliminationFraction) {
        if (status != WindowStatus.OPEN) {
            throw new IllegalStateException("Окно уже закрыто: " + id);
        }
        if (now.isBefore(closesAt)) {
            throw new IllegalStateException("Окно открыто до " + closesAt);
        }
        status = WindowStatus.CLOSED;
        List<WindowParticipant> ranked = ParticipantRanking.rank(participantsByEntry.values());
        int eliminatedCount = algorithm.eliminatedCount(ranked.size(), eliminationFraction);
        List<UUID> eliminated = new ArrayList<>(eliminatedCount);
        for (int i = 0; i < eliminatedCount; i++) {
            WindowParticipant worst = ranked.get(ranked.size() - 1 - i);
            worst.eliminate();
            eliminated.add(worst.entryId());
        }
        int survivorCount = ranked.size() - eliminatedCount;
        List<UUID> survived = new ArrayList<>(survivorCount);
        UUID winnerEntryId = null;
        for (int i = 0; i < survivorCount; i++) {
            WindowParticipant survivor = ranked.get(i);
            if (survivorCount == 1) {
                survivor.declareWinner();
                winnerEntryId = survivor.entryId();
            } else {
                survivor.survive();
                survived.add(survivor.entryId());
            }
        }
        return new CloseOutcome(List.copyOf(eliminated), List.copyOf(survived), winnerEntryId);
    }
```

```java
    /** Итог закрытия: выбывшие; выжившие (≥ 2 → следующий раунд) или победитель. */
    public record CloseOutcome(List<UUID> eliminatedEntryIds, List<UUID> survivedEntryIds,
                               UUID winnerEntryId) {
    }
```

`Tournament` — добавить метод (после `cancelForInsufficientParticipants`):

```java
    /** Завершение: победитель определён закрытием окна (раздел 7). */
    public void finish(Instant now) {
        requireStatus(TournamentStatus.RUNNING);
        status = TournamentStatus.FINISHED;
    }
```

`TournamentEntry` — поле `status` перестаёт быть `final`, добавить методы:

```java
    /** Выбывание (закрытие окна, раздел 7): ACTIVE → ELIMINATED. */
    public void eliminate() {
        requireActive();
        status = EntryStatus.ELIMINATED;
    }

    /** Победа (закрытие окна, раздел 7): ACTIVE → WINNER. */
    public void declareWinner() {
        requireActive();
        status = EntryStatus.WINNER;
    }

    private void requireActive() {
        if (status != EntryStatus.ACTIVE) {
            throw new IllegalStateException("Итог участия уже зафиксирован: " + status);
        }
    }
```

`TournamentEntryRepository` — добавить:

```java
    /** Участие по id (закрытие окна, раздел 12.3). */
    Optional<TournamentEntry> findById(UUID id);
```

- [ ] **Step 5: Запустить — зелёный**

```bash
./mvnw -q test -Dtest='RoundEliminationTest,ParticipantRankingTest,VotingWindowTest,TournamentTest,TournamentEntryTest'
```

Ожидание: PASS. Затем `./mvnw -q test` — весь unit-контур зелёный (существующие тесты не сломаны: `TournamentEntry` mutable status не меняет старое поведение).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/plantarena/tournaments/domain \
  src/test/java/com/plantarena/tournaments/domain
git commit -m "feat(voting): домен — RoundElimination, ParticipantRanking, закрытие окна, финал турнира"
```

---

### Task 4: Application — голосование (права, дельты, идемпотентное удаление)

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/application/port/in/VotingUseCase.java`
- Create: `src/main/java/com/plantarena/tournaments/application/VotingService.java`
- Create: `src/main/java/com/plantarena/tournaments/application/WindowNotFoundException.java`
- Create: `src/main/java/com/plantarena/tournaments/application/EntryNotInWindowException.java`
- Create: `src/main/java/com/plantarena/tournaments/application/VotingClosedException.java`
- Create: `src/main/java/com/plantarena/tournaments/application/SelfVoteForbiddenException.java`
- Create: `src/main/java/com/plantarena/tournaments/application/UnknownVoteValueException.java`
- Create: `src/test/java/com/plantarena/tournaments/application/support/InMemoryVotingWindowRepository.java`
- Test: `src/test/java/com/plantarena/tournaments/application/VotingServiceTest.java`

**Interfaces:**
- Consumes: домен Task 2, `TournamentsAccessPolicy`, `TournamentRepository`, `InvitationRepository`, `TournamentEntryRepository` (существуют), `CurrentActor(UUID userId, Set<AppRole> roles, boolean isGuest)`.
- Produces (для Tasks 7): `VotingUseCase { long cast(CurrentActor, UUID windowId, UUID entryId, String value); long remove(CurrentActor, UUID windowId, UUID entryId); Optional<String> myVote(CurrentActor, UUID windowId, UUID entryId); }`; исключения с HTTP-кодами: `WindowNotFoundException` 404, `EntryNotInWindowException` 404, `VotingClosedException` 409, `SelfVoteForbiddenException` 403, `UnknownVoteValueException` 400; `InMemoryVotingWindowRepository` (для application-тестов Tasks 4–5).

- [ ] **Step 1: Красный `VotingServiceTest`**

`src/test/java/com/plantarena/tournaments/application/VotingServiceTest.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.application.support.InMemoryVotingWindowRepository;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingSubject;
import com.plantarena.tournaments.domain.VotingWindow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Голосование (раздел 9 + допущения 6/9): права, дельты, идемпотентность. */
@DisplayName("Голосование: участник турнира, не сам, окно открыто")
class VotingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations = new InMemoryInvitationRepository();
    private final InMemoryTournamentEntryRepository entries = new InMemoryTournamentEntryRepository();
    private final InMemoryVotingWindowRepository windows = new InMemoryVotingWindowRepository();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final VotingService service = new VotingService(windows, tournaments, invitations,
        entries, new TournamentsAccessPolicy(), clock);

    private UUID tournamentId;
    private UUID windowId;
    private UUID entry1;
    private UUID entry2;
    private UUID entry3;
    private UUID user1;
    private UUID user2;
    private UUID user3;
    private UUID organizer;

    @BeforeEach
    void setUp() {
        organizer = UUID.randomUUID();
        user1 = UUID.randomUUID();
        user2 = UUID.randomUUID();
        user3 = UUID.randomUUID();
        Instant deadline = NOW.minusSeconds(60); // дедлайн прошёл — турнир RUNNING
        Tournament tournament = Tournament.createDraft(organizer, "Турнир", null,
            deadline, Duration.ofSeconds(3600), 0.5, 2, Set.of(), deadline.minusSeconds(600));
        tournament.openRegistration(deadline.minusSeconds(599));
        tournament.start(NOW, 3);
        tournaments.save(tournament);
        tournamentId = tournament.id();

        entry1 = entries.save(TournamentEntry.admit(tournamentId, user1, UUID.randomUUID(),
            UUID.randomUUID(), NOW)).id();
        entry2 = entries.save(TournamentEntry.admit(tournamentId, user2, UUID.randomUUID(),
            UUID.randomUUID(), NOW)).id();
        entry3 = entries.save(TournamentEntry.admit(tournamentId, user3, UUID.randomUUID(),
            UUID.randomUUID(), NOW)).id();

        VotingWindow window = VotingWindow.open(tournamentId, 1, seeds(), NOW,
            NOW.plusSeconds(3600), NOW);
        windows.save(window);
        windowId = window.id();
    }

    @Test
    @DisplayName("участник голосует LIKE → 200-контракт: новый счёт 1")
    void участник_голосует() {
        assertThat(service.cast(actor(user1), windowId, entry2, "LIKE")).isEqualTo(1L);
        assertThat(service.myVote(actor(user1), windowId, entry2)).contains("LIKE");
    }

    @Test
    @DisplayName("выбывший участник продолжает голосовать до завершения (допущение 9)")
    void выбывший_голосует() {
        entries.save(TournamentEntry.restore(entry1, tournamentId, user1,
            UUID.randomUUID(), UUID.randomUUID(),
            com.plantarena.tournaments.domain.EntryStatus.ELIMINATED, NOW));
        assertThat(service.cast(actor(user1), windowId, entry2, "LIKE")).isEqualTo(1L);
    }

    @Test
    @DisplayName("организатор без участия — 403; посторонний — 404 (турнир скрыт)")
    void не_участник_не_голосует() {
        assertThatThrownBy(() -> service.cast(actor(organizer), windowId, entry2, "LIKE"))
            .isInstanceOf(com.plantarena.shared.security.AccessDeniedException.class);
        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> service.cast(actor(stranger), windowId, entry2, "LIKE"))
            .isInstanceOf(TournamentNotFoundException.class);
    }

    @Test
    @DisplayName("гость — 401 (гостевые сессии — итерация 8)")
    void гость_не_голосует() {
        CurrentActor guest = new CurrentActor(null, Set.of(), true);
        assertThatThrownBy(() -> service.cast(guest, windowId, entry2, "LIKE"))
            .isInstanceOf(com.plantarena.shared.security.NotIdentifiedException.class);
    }

    @Test
    @DisplayName("самоголосование запрещено (допущение 6)")
    void самоголосование() {
        assertThatThrownBy(() -> service.cast(actor(user1), windowId, entry1, "LIKE"))
            .isInstanceOf(SelfVoteForbiddenException.class);
    }

    @Test
    @DisplayName("entry не из окна — 404; неизвестное окно — 404")
    void entry_и_окно() {
        assertThatThrownBy(() -> service.cast(actor(user1), windowId, UUID.randomUUID(), "LIKE"))
            .isInstanceOf(EntryNotInWindowException.class);
        assertThatThrownBy(() -> service.cast(actor(user1), UUID.randomUUID(), entry2, "LIKE"))
            .isInstanceOf(WindowNotFoundException.class);
    }

    @Test
    @DisplayName("окно закрыто или дедлайн истёк — 409 (в closesAt голос уже запрещён)")
    void закрытое_окно() {
        VotingWindow closed = VotingWindow.open(tournamentId, 2, seeds(),
            NOW.minusSeconds(7200), NOW.minusSeconds(3600), NOW.minusSeconds(7200));
        closed.close(NOW.minusSeconds(3600),
            new com.plantarena.tournaments.domain.RoundElimination(), 0.5);
        windows.save(closed);
        assertThatThrownBy(() -> service.cast(actor(user1), closed.id(), entry2, "LIKE"))
            .isInstanceOf(VotingClosedException.class);

        VotingWindow expiring = VotingWindow.open(tournamentId, 3, seeds(),
            NOW.minusSeconds(10), NOW, NOW.minusSeconds(10));
        windows.save(expiring);
        assertThatThrownBy(() -> service.cast(actor(user1), expiring.id(), entry2, "LIKE"))
            .isInstanceOf(VotingClosedException.class);
    }

    @Test
    @DisplayName("неизвестное значение голоса — 400 (LIKE/DISLIKE)")
    void неизвестное_значение() {
        assertThatThrownBy(() -> service.cast(actor(user1), windowId, entry2, "APPLAUSE"))
            .isInstanceOf(UnknownVoteValueException.class);
        assertThatThrownBy(() -> service.cast(actor(user1), windowId, entry2, null))
            .isInstanceOf(UnknownVoteValueException.class);
    }

    @Test
    @DisplayName("удаление компенсирует вклад и идемпотентно; myVote — null")
    void удаление() {
        service.cast(actor(user1), windowId, entry2, "DISLIKE");
        assertThat(service.remove(actor(user1), windowId, entry2)).isZero();
        assertThat(service.remove(actor(user1), windowId, entry2)).isZero();
        assertThat(service.myVote(actor(user1), windowId, entry2)).isEmpty();
    }

    @Test
    @DisplayName("LIKE → DISLIKE даёт −2 (дельта применяется к счёту участника)")
    void смена_знака() {
        assertThat(service.cast(actor(user1), windowId, entry2, "LIKE")).isEqualTo(1L);
        assertThat(service.cast(actor(user1), windowId, entry2, "DISLIKE")).isEqualTo(-1L);
    }

    private java.util.List<VotingWindow.ParticipantSeed> seeds() {
        return java.util.List.of(
            new VotingWindow.ParticipantSeed(entry1, user1, NOW),
            new VotingWindow.ParticipantSeed(entry2, user2, NOW),
            new VotingWindow.ParticipantSeed(entry3, user3, NOW));
    }

    private CurrentActor actor(UUID userId) {
        return new CurrentActor(userId, Set.of(AppRole.USER), false);
    }
}
```

Проверить перед реализацией: `InMemoryTournamentRepository.save/trackEntry`, `TournamentEntry.restore(...)` — сигнатуры Task 3; `InMemoryInvitationRepository` существует (пустой — посторонний не приглашён).

- [ ] **Step 2: Запустить — красный**

```bash
./mvnw -q test -Dtest='VotingServiceTest'
```

Ожидание: FAIL (компиляция): `cannot find symbol: class VotingService`.

- [ ] **Step 3: Реализация**

`src/main/java/com/plantarena/tournaments/application/port/in/VotingUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.Optional;
import java.util.UUID;

/** Голосование в окне (раздел 9): PUT/DELETE/my-vote. */
public interface VotingUseCase {

    /** Установить голос (LIKE/DISLIKE); возвращает новый счёт участника. */
    long cast(CurrentActor actor, UUID windowId, UUID entryId, String value);

    /** Удалить свой голос (идемпотентно); возвращает счёт после удаления. */
    long remove(CurrentActor actor, UUID windowId, UUID entryId);

    /** Текущий голос субъекта за участника (пусто — голоса нет). */
    Optional<String> myVote(CurrentActor actor, UUID windowId, UUID entryId);
}
```

Исключения (все — `RuntimeException` с конструктором сообщения, по образцу `TournamentNotFoundException`):

```java
package com.plantarena.tournaments.application;

/** Окно не найдено или скрыто политикой приватности (404). */
public final class WindowNotFoundException extends RuntimeException {
    public WindowNotFoundException(String message) {
        super(message);
    }
}
```

```java
package com.plantarena.tournaments.application;

/** Участие не входит в состав окна (404). */
public final class EntryNotInWindowException extends RuntimeException {
    public EntryNotInWindowException(String message) {
        super(message);
    }
}
```

```java
package com.plantarena.tournaments.application;

/** Окно закрыто или дедлайн истёк — интервал [opensAt, closesAt) (409). */
public final class VotingClosedException extends RuntimeException {
    public VotingClosedException(String message) {
        super(message);
    }
}
```

```java
package com.plantarena.tournaments.application;

/** Самоголосование запрещено для идентифицированного пользователя (403, допущение 6). */
public final class SelfVoteForbiddenException extends RuntimeException {
    public SelfVoteForbiddenException(String message) {
        super(message);
    }
}
```

```java
package com.plantarena.tournaments.application;

/** Неизвестное значение голоса — допустимо LIKE/DISLIKE (400). */
public final class UnknownVoteValueException extends RuntimeException {
    public UnknownVoteValueException(String message) {
        super(message);
    }
}
```

`src/main/java/com/plantarena/tournaments/application/VotingService.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.VotingUseCase;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingSubject;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Голосование (разделы 9, 12.1): одна tx — один агрегат VotingWindow.
 * Блокировка окна (findByIdForUpdate) сериализует голоса и закрытие; время
 * проверяется после блокировки. Права: участник, допущенный к старту
 * (включая выбывшего — допущение 9); посторонний — 404 (турнир скрыт),
 * организатор-не-участник — 403, гость — 401 (GUEST — итерация 8),
 * самоголосование — 403 (допущение 6).
 */
@Service
public class VotingService implements VotingUseCase {

    private static final Set<InvitationStatus> ACTIVE_INVITATION_STATUSES = Set.of(
        InvitationStatus.INVITED, InvitationStatus.ACCEPTED_PENDING_MODERATION,
        InvitationStatus.READY);

    private final VotingWindowRepository windows;
    private final TournamentRepository tournaments;
    private final InvitationRepository invitations;
    private final TournamentEntryRepository entries;
    private final TournamentsAccessPolicy accessPolicy;
    private final Clock clock;

    public VotingService(VotingWindowRepository windows, TournamentRepository tournaments,
                         InvitationRepository invitations, TournamentEntryRepository entries,
                         TournamentsAccessPolicy accessPolicy, Clock clock) {
        this.windows = windows;
        this.tournaments = tournaments;
        this.invitations = invitations;
        this.entries = entries;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public long cast(CurrentActor actor, UUID windowId, UUID entryId, String value) {
        VotingSubject subject = subjectOf(actor);
        VoteValue voteValue = parseValue(value);
        VotingWindow window = lock(windowId);
        requireVoter(actor, findTournament(window.tournamentId()));
        checkVote(window, entryId, actor);
        long score = window.castVote(subject, entryId, voteValue, clock.instant());
        windows.save(window);
        return score;
    }

    @Override
    @Transactional
    public long remove(CurrentActor actor, UUID windowId, UUID entryId) {
        VotingSubject subject = subjectOf(actor);
        VotingWindow window = lock(windowId);
        requireVoter(actor, findTournament(window.tournamentId()));
        checkVote(window, entryId, actor);
        long score = window.removeVote(subject, entryId, clock.instant());
        windows.save(window);
        return score;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> myVote(CurrentActor actor, UUID windowId, UUID entryId) {
        subjectOf(actor);
        VotingWindow window = windows.findById(windowId)
            .orElseThrow(() -> new WindowNotFoundException("Окно не найдено: " + windowId));
        requireVoter(actor, findTournament(window.tournamentId()));
        if (!window.hasEntry(entryId)) {
            throw new EntryNotInWindowException("Участие не входит в окно: " + entryId);
        }
        VoteValue value = window.myVote(VotingSubject.user(actor.userId()).subjectKey(), entryId);
        return Optional.ofNullable(value).map(Enum::name);
    }

    private VotingSubject subjectOf(CurrentActor actor) {
        accessPolicy.requireIdentified(actor); // гость — 401; GUEST — итерация 8
        return VotingSubject.user(actor.userId());
    }

    private VoteValue parseValue(String value) {
        if (value == null) {
            throw new UnknownVoteValueException("Значение голоса обязательно (LIKE/DISLIKE)");
        }
        try {
            return VoteValue.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new UnknownVoteValueException(
                "Неизвестное значение голоса: " + value + " (допустимо: LIKE, DISLIKE)");
        }
    }

    private void requireVoter(CurrentActor actor, Tournament tournament) {
        accessPolicy.requireTournamentViewer(actor, tournament,
            visibleBeyondOrganizer(actor, tournament.id()));
        if (!entries.existsByTournamentIdAndUserId(tournament.id(), actor.userId())) {
            throw new AccessDeniedException(
                "Голосовать может только участник, допущенный к старту (допущение 9)");
        }
    }

    private void checkVote(VotingWindow window, UUID entryId, CurrentActor actor) {
        if (!window.isAcceptingVotes(clock.instant())) {
            throw new VotingClosedException(
                "Окно закрыто или дедлайн истёк (интервал [opensAt, closesAt))");
        }
        if (!window.hasEntry(entryId)) {
            throw new EntryNotInWindowException("Участие не входит в окно: " + entryId);
        }
        if (window.userIdOfEntry(entryId).equals(actor.userId())) {
            throw new SelfVoteForbiddenException("Самоголосование запрещено (допущение 6)");
        }
    }

    private boolean visibleBeyondOrganizer(CurrentActor actor, UUID tournamentId) {
        boolean invited = invitations.findByTournamentIdAndUserId(tournamentId, actor.userId())
            .map(invitation -> ACTIVE_INVITATION_STATUSES.contains(invitation.status()))
            .orElse(false);
        return invited || entries.existsByTournamentIdAndUserId(tournamentId, actor.userId());
    }

    private VotingWindow lock(UUID windowId) {
        return windows.findByIdForUpdate(windowId)
            .orElseThrow(() -> new WindowNotFoundException("Окно не найдено: " + windowId));
    }

    private Tournament findTournament(UUID tournamentId) {
        return tournaments.findById(tournamentId)
            .orElseThrow(() -> new TournamentNotFoundException(
                "Турнир не найден: " + tournamentId));
    }
}
```

`src/test/java/com/plantarena/tournaments/application/support/InMemoryVotingWindowRepository.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import com.plantarena.tournaments.domain.WindowStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory фейк VotingWindowRepository (блокировка — no-op, как в монолите). */
public class InMemoryVotingWindowRepository implements VotingWindowRepository {

    public final Map<UUID, VotingWindow> windows = new ConcurrentHashMap<>();

    @Override
    public VotingWindow save(VotingWindow window) {
        windows.put(window.id(), window);
        return window;
    }

    @Override
    public Optional<VotingWindow> findById(UUID id) {
        return Optional.ofNullable(windows.get(id));
    }

    @Override
    public Optional<VotingWindow> findByIdForUpdate(UUID id) {
        return findById(id);
    }

    @Override
    public List<UUID> findDueForClose(Instant now, int limit) {
        return windows.values().stream()
            .filter(window -> window.status() == WindowStatus.OPEN
                && !now.isBefore(window.closesAt()))
            .sorted(Comparator.comparing(VotingWindow::closesAt))
            .limit(limit)
            .map(VotingWindow::id)
            .toList();
    }

    @Override
    public List<VotingWindow> findByTournamentId(UUID tournamentId, int offset, int size) {
        return windows.values().stream()
            .filter(window -> window.tournamentId().equals(tournamentId))
            .sorted(Comparator.comparingInt(VotingWindow::sequence))
            .skip(offset).limit(size).toList();
    }

    @Override
    public long countByTournamentId(UUID tournamentId) {
        return windows.values().stream()
            .filter(window -> window.tournamentId().equals(tournamentId)).count();
    }

    @Override
    public Optional<VotingWindow> findLatestByTournamentId(UUID tournamentId) {
        return windows.values().stream()
            .filter(window -> window.tournamentId().equals(tournamentId))
            .max(Comparator.comparingInt(VotingWindow::sequence));
    }

    @Override
    public List<VotingWindow> findAllByTournamentId(UUID tournamentId) {
        return findByTournamentId(tournamentId, 0, Integer.MAX_VALUE);
    }
}
```

- [ ] **Step 4: Запустить — зелёный**

```bash
./mvnw -q test -Dtest='VotingServiceTest'
```

Ожидание: PASS (11 тестов).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/plantarena/tournaments/application \
  src/test/java/com/plantarena/tournaments/application
git commit -m "feat(voting): application — голосование (права, дельты, идемпотентное удаление)"
```

---

### Task 5: Application — закрытие окон, первый раунд при старте, события, запросы

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/api/event/EntryEliminatedEvent.java`
- Create: `src/main/java/com/plantarena/tournaments/api/event/TournamentFinishedEvent.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/in/CloseVotingWindowUseCase.java`
- Create: `src/main/java/com/plantarena/tournaments/application/CloseVotingWindowService.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/in/ListRoundsUseCase.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/in/GetLeaderboardUseCase.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/in/ListResultsUseCase.java`
- Create: `src/main/java/com/plantarena/tournaments/application/VotingQueryService.java`
- Create: `src/test/java/com/plantarena/tournaments/application/support/FakePlantLifecycleGateway.java`
- Modify: `src/main/java/com/plantarena/tournaments/application/StartTournamentService.java`
- Test: `src/test/java/com/plantarena/tournaments/application/CloseVotingWindowServiceTest.java`
- Test: `src/test/java/com/plantarena/tournaments/application/VotingQueryServiceTest.java`
- Test: `src/test/java/com/plantarena/tournaments/application/StartTournamentServiceTest.java` (обновить)

**Interfaces:**
- Consumes: домен Tasks 2–3, `PlantEligibilityGateway`, `PlantLifecycleGateway` (Task 1), `IntegrationEventPublisher`, `TransactionTemplate` (как `StartTournamentService`).
- Produces (для Tasks 7–8): `CloseVotingWindowUseCase { int closeDue(Instant now, int limit); }`; события `EntryEliminatedEvent(TYPE="EntryEliminated", Payload(tournamentId, entryId, userId, plantId, windowSequence))`, `TournamentFinishedEvent(TYPE="TournamentFinished", Payload(tournamentId, winnerEntryId, winnerUserId, winnerPlantId))`; `ListRoundsUseCase.RoundListResult(List<RoundData(id, sequence, status, opensAt, closesAt)>, total)`, `GetLeaderboardUseCase.LeaderboardResult(windowId, sequence, status, closesAt, List<LeaderboardEntry(position, entryId, userId, score, result)>, total)`, `ListResultsUseCase.ResultListResult(winnerEntryId, List<ResultData(entryId, userId, plantId, status, eliminatedInRound)>, total)`; `FakePlantLifecycleGateway` с записью вызовов.

- [ ] **Step 1: События published language**

`src/main/java/com/plantarena/tournaments/api/event/EntryEliminatedEvent.java`:

```java
package com.plantarena.tournaments.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованное событие: участник выбыл из турнира (раздел 16). Агрегат
 * процесса — закрывшееся окно. Подписчики — лента (итерация 8) и
 * notification-service лабы №4.
 */
public record EntryEliminatedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "EntryEliminated";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID tournamentId, UUID entryId, UUID userId, UUID plantId,
                          int windowSequence) {
    }
}
```

`src/main/java/com/plantarena/tournaments/api/event/TournamentFinishedEvent.java`:

```java
package com.plantarena.tournaments.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованное событие: турнир завершён, победитель определён (раздел 16).
 * Подписчики — лента (итерация 8) и notification-service лабы №4.
 */
public record TournamentFinishedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "TournamentFinished";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID tournamentId, UUID winnerEntryId, UUID winnerUserId,
                          UUID winnerPlantId) {
    }
}
```

- [ ] **Step 2: Красный `CloseVotingWindowServiceTest`**

`src/test/java/com/plantarena/tournaments/application/support/FakePlantLifecycleGateway.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.application.port.out.PlantLifecycleGateway;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Фейк порта гибели: запись вызовов для утверждений о командах plants. */
public class FakePlantLifecycleGateway implements PlantLifecycleGateway {

    public final List<DeathCall> calls = new ArrayList<>();

    @Override
    public void registerDeath(UUID plantId, RestrictionKind kind, Instant cooldownExpiresAt,
                              String reason, UUID sourceEntryId) {
        calls.add(new DeathCall(plantId, kind, cooldownExpiresAt, reason, sourceEntryId));
    }

    public boolean isDead(UUID plantId) {
        return calls.stream().anyMatch(call -> call.plantId().equals(plantId));
    }

    public record DeathCall(UUID plantId, RestrictionKind kind, Instant cooldownExpiresAt,
                            String reason, UUID sourceEntryId) {
    }
}
```

`src/test/java/com/plantarena/tournaments/application/CloseVotingWindowServiceTest.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.event.IntegrationEventPublisher;
import com.plantarena.tournaments.api.event.EntryEliminatedEvent;
import com.plantarena.tournaments.api.event.TournamentFinishedEvent;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.application.port.out.PlantLifecycleGateway;
import com.plantarena.tournaments.application.support.FakeEventPublisher;
import com.plantarena.tournaments.application.support.FakePlantEligibilityGateway;
import com.plantarena.tournaments.application.support.FakePlantLifecycleGateway;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.application.support.InMemoryVotingWindowRepository;
import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentStatus;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingSubject;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.WindowStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Закрытие окон (разделы 7, 12.3): выбывание, гибель, резервы, следующий раунд. */
@DisplayName("Закрытие окна: идемпотентно, с гибелью и следующим раундом")
class CloseVotingWindowServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations = new InMemoryInvitationRepository();
    private final InMemoryTournamentEntryRepository entries =
        new InMemoryTournamentEntryRepository();
    private final InMemoryVotingWindowRepository windows = new InMemoryVotingWindowRepository();
    private final FakePlantEligibilityGateway eligibility = new FakePlantEligibilityGateway();
    private final FakePlantLifecycleGateway plantLifecycle = new FakePlantLifecycleGateway();
    private final FakeEventPublisher eventPublisher = new FakeEventPublisher();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private UUID tournamentId;
    private UUID entry1;
    private UUID entry2;
    private UUID entry3;
    private UUID plant1;
    private UUID plant2;
    private UUID plant3;
    private UUID reservation1;
    private UUID reservation2;
    private UUID reservation3;
    private CloseVotingWindowService service;

    @BeforeEach
    void setUp() {
        UUID organizer = UUID.randomUUID();
        Instant deadline = NOW.minusSeconds(3600);
        Tournament tournament = Tournament.createDraft(organizer, "Турнир", null,
            deadline, Duration.ofSeconds(60), 0.5, 2, Set.of(), deadline.minusSeconds(600));
        tournament.openRegistration(deadline.minusSeconds(599));
        tournament.start(NOW, 3);
        tournaments.save(tournament);
        tournamentId = tournament.id();

        entry1 = admit(UUID.randomUUID(), reservation1 = UUID.randomUUID(), plant1 = UUID.randomUUID());
        entry2 = admit(UUID.randomUUID(), reservation2 = UUID.randomUUID(), plant2 = UUID.randomUUID());
        entry3 = admit(UUID.randomUUID(), reservation3 = UUID.randomUUID(), plant3 = UUID.randomUUID());

        service = new CloseVotingWindowService(windows, tournaments, entries, eligibility,
            plantLifecycle, eventPublisher, clock, txTemplate());
    }

    @Test
    @DisplayName("закрытие due-окна: худший выбывает, гибель PERMANENT, резерв освобождён, следующий раунд")
    void закрытие_с_выбыванием() {
        VotingWindow window = openDueWindow();
        window.castVote(VotingSubject.user(UUID.randomUUID()), entry2, VoteValue.LIKE, NOW);
        windows.save(window);
        // счёт: entry2 +1, entry3 0, entry1 −1... голосуем против entry1:
        window.castVote(VotingSubject.user(UUID.randomUUID()), entry1, VoteValue.DISLIKE, NOW);
        windows.save(window);

        int processed = service.closeDue(NOW, 10);

        assertThat(processed).isEqualTo(1);
        assertThat(statusOf(entry1)).isEqualTo(EntryStatus.ELIMINATED);
        assertThat(statusOf(entry2)).isEqualTo(EntryStatus.ACTIVE);
        assertThat(plantLifecycle.isDead(plant1)).isTrue();
        assertThat(plantLifecycle.calls).singleElement()
            .satisfies(call -> {
                assertThat(call.kind()).isEqualTo(PlantLifecycleGateway.RestrictionKind.PERMANENT);
                assertThat(call.cooldownExpiresAt()).isNull();
                assertThat(call.sourceEntryId()).isEqualTo(entry1);
            });
        assertThat(eligibility.isReleased(reservation1)).isTrue();
        assertThat(eligibility.isReleased(reservation2)).isFalse();
        assertThat(eventPublisher.published).anySatisfy(event ->
            assertThat(event).isInstanceOf(EntryEliminatedEvent.class));

        // следующий раунд: sequence 2, выжившие, счёт с нуля, OPEN
        VotingWindow next = windows.findLatestByTournamentId(tournamentId).orElseThrow();
        assertThat(next.sequence()).isEqualTo(2);
        assertThat(next.status()).isEqualTo(WindowStatus.OPEN);
        assertThat(next.hasEntry(entry1)).isFalse();
        assertThat(next.hasEntry(entry2)).isTrue();
        assertThat(next.scoreOf(entry2)).isZero();
        assertThat(tournaments.findById(tournamentId).orElseThrow().status())
            .isEqualTo(TournamentStatus.RUNNING);
    }

    @Test
    @DisplayName("один выживший — WINNER, турнир FINISHED, резерв победителя освобождён")
    void победитель() {
        // окно с двумя участниками: entry2 LIKE от субъекта, entry1 без голосов
        VotingWindow window = VotingWindow.open(tournamentId, 1, List.of(
            new VotingWindow.ParticipantSeed(entry1, UUID.randomUUID(), NOW),
            new VotingWindow.ParticipantSeed(entry2, UUID.randomUUID(), NOW)),
            NOW.minusSeconds(120), NOW.minusSeconds(60), NOW.minusSeconds(120));
        window.castVote(VotingSubject.user(UUID.randomUUID()), entry2, VoteValue.LIKE,
            NOW.minusSeconds(100));
        windows.save(window);

        service.closeDue(NOW, 10);

        assertThat(statusOf(entry1)).isEqualTo(EntryStatus.ELIMINATED);
        assertThat(statusOf(entry2)).isEqualTo(EntryStatus.WINNER);
        assertThat(tournaments.findById(tournamentId).orElseThrow().status())
            .isEqualTo(TournamentStatus.FINISHED);
        assertThat(eligibility.isReleased(reservation2)).isTrue();
        assertThat(eventPublisher.published).anySatisfy(event ->
            assertThat(event).isInstanceOf(TournamentFinishedEvent.class));
        assertThat(windows.countByTournamentId(tournamentId)).isEqualTo(1); // нового окна нет
    }

    @Test
    @DisplayName("повторное закрытие того же окна — no-op: без повторной гибели и без дубля раунда")
    void повторное_закрытие_идемпотентно() {
        openDueWindow();
        service.closeDue(NOW, 10);
        int deathCount = plantLifecycle.calls.size();
        int windowCount = (int) windows.countByTournamentId(tournamentId);

        int processed = service.closeDue(NOW, 10);

        assertThat(processed).isZero();
        assertThat(plantLifecycle.calls).hasSize(deathCount);
        assertThat(windows.countByTournamentId(tournamentId)).isEqualTo(windowCount);
    }

    @Test
    @DisplayName("не-due окно не закрывается (closesAt в будущем)")
    void не_due_окно() {
        windows.save(VotingWindow.open(tournamentId, 1, seeds(), NOW,
            NOW.plusSeconds(60), NOW));
        assertThat(service.closeDue(NOW, 10)).isZero();
        assertThat(windows.findLatestByTournamentId(tournamentId).orElseThrow().status())
            .isEqualTo(WindowStatus.OPEN);
    }

    private VotingWindow openDueWindow() {
        VotingWindow window = VotingWindow.open(tournamentId, 1, seeds(),
            NOW.minusSeconds(120), NOW.minusSeconds(60), NOW.minusSeconds(120));
        windows.save(window);
        return window;
    }

    private UUID admit(UUID userId, UUID reservationId, UUID plantId) {
        return entries.save(TournamentEntry.admit(tournamentId, userId, plantId,
            reservationId, NOW)).id();
    }

    private EntryStatus statusOf(UUID entryId) {
        return entries.entries.get(entryId).status();
    }

    private List<VotingWindow.ParticipantSeed> seeds() {
        return List.of(
            new VotingWindow.ParticipantSeed(entry1, UUID.randomUUID(), NOW),
            new VotingWindow.ParticipantSeed(entry2, UUID.randomUUID(), NOW),
            new VotingWindow.ParticipantSeed(entry3, UUID.randomUUID(), NOW));
    }

    private org.springframework.transaction.support.TransactionTemplate txTemplate() {
        return new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.transaction.support.SimpleTransactionStatus() == null
                ? null : null);
    }
}
```

**Важно:** `txTemplate()` выше — заглушка; в тесте используйте реальный `TransactionTemplate` без платформы: он выполняет колбэк напрямую, если нет менеджера транзакций — создайте через `new TransactionTemplate(new SimpleTransactionStatus?)` нельзя. Правильный вариант (как в `StartTournamentServiceTest` итерации 5 — свериться с ним): если там используется `new TransactionTemplate()` без аргументов с переопределённым `executeWithoutResult`, повторить тот же приём; иначе создать `TransactionTemplate` с no-op `PlatformTransactionManager`:

```java
    private TransactionTemplate txTemplate() {
        return new TransactionTemplate(new NoopPlatformTransactionManager());
    }

    /** No-op менеджер для unit-тестов (без Spring-контекста). */
    private static final class NoopPlatformTransactionManager
            implements org.springframework.transaction.PlatformTransactionManager {

        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(
                org.springframework.transaction.TransactionDefinition definition) {
            return new org.springframework.transaction.support.SimpleTransactionStatus();
        }

        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {
        }

        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {
        }
    }
```

Перед написанием теста свериться с `StartTournamentServiceTest`: если там уже есть такой no-op менеджер/фабрика — переиспользовать (вынести в `support` как `TestTransactionTemplates.noop()`).

- [ ] **Step 3: Запустить — красный**

```bash
./mvnw -q test -Dtest='CloseVotingWindowServiceTest'
```

Ожидание: FAIL (компиляция): `cannot find symbol: class CloseVotingWindowService`.

- [ ] **Step 4: Реализация `CloseVotingWindowService`**

`src/main/java/com/plantarena/tournaments/application/port/in/CloseVotingWindowUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import java.time.Instant;

/** Закрытие просроченных окон (разделы 7, 12.3): scheduler и demo-ручка. */
public interface CloseVotingWindowUseCase {

    /**
     * Закрыть due-окна (closesAt &lt;= now), пачка ≤ limit; возвращает число
     * закрытых. Идемпотентен: повтор для закрытого окна — no-op.
     */
    int closeDue(Instant now, int limit);
}
```

`src/main/java/com/plantarena/tournaments/application/CloseVotingWindowService.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.event.IntegrationEventPublisher;
import com.plantarena.tournaments.api.event.EntryEliminatedEvent;
import com.plantarena.tournaments.api.event.TournamentFinishedEvent;
import com.plantarena.tournaments.application.port.in.CloseVotingWindowUseCase;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.application.port.out.PlantLifecycleGateway;
import com.plantarena.tournaments.domain.EliminationAlgorithms;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import com.plantarena.tournaments.domain.WindowStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Один use case закрытия окон для scheduler'а и demo-ручки (раздел 12.3,
 * дизайн итерации 6): каждое окно — в отдельной короткой tx; один сбой не
 * блокирует остальные. Внутри tx (ADR-011): блокировка окна → повтор для
 * CLOSED — no-op → закрытие доменом (рейтинг + выбывание) → entry
 * ELIMINATED/WINNER → команды plants (гибель PERMANENT, освобождение
 * резервов; растения в устойчивом порядке по plantId) → следующий раунд или
 * FINISHED + TournamentFinished. События EntryEliminated/TournamentFinished
 * публикуются в той же tx.
 */
@Service
public class CloseVotingWindowService implements CloseVotingWindowUseCase {

    private static final Logger log = LoggerFactory.getLogger(CloseVotingWindowService.class);

    private final VotingWindowRepository windows;
    private final TournamentRepository tournaments;
    private final TournamentEntryRepository entries;
    private final PlantEligibilityGateway eligibility;
    private final PlantLifecycleGateway plantLifecycle;
    private final IntegrationEventPublisher eventPublisher;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public CloseVotingWindowService(VotingWindowRepository windows,
                                    TournamentRepository tournaments,
                                    TournamentEntryRepository entries,
                                    PlantEligibilityGateway eligibility,
                                    PlantLifecycleGateway plantLifecycle,
                                    IntegrationEventPublisher eventPublisher, Clock clock,
                                    TransactionTemplate transactionTemplate) {
        this.windows = windows;
        this.tournaments = tournaments;
        this.entries = entries;
        this.eligibility = eligibility;
        this.plantLifecycle = plantLifecycle;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int closeDue(Instant now, int limit) {
        int processed = 0;
        for (UUID windowId : windows.findDueForClose(now, limit)) {
            try {
                transactionTemplate.executeWithoutResult(
                    status -> closeOne(windowId, clock.instant()));
                processed++;
            } catch (RuntimeException e) {
                // одно окно не блокирует остальные; повтор — следующий poll
                log.warn("Закрытие окна {} не удалось, будет повторено: {}", windowId,
                    e.getMessage());
            }
        }
        return processed;
    }

    private void closeOne(UUID windowId, Instant now) {
        VotingWindow window = windows.findByIdForUpdate(windowId)
            .orElseThrow(() -> new WindowNotFoundException("Окно не найдено: " + windowId));
        if (window.status() != WindowStatus.OPEN || now.isBefore(window.closesAt())) {
            return; // уже закрыто (идемпотентность повтора) или ещё не due
        }
        Tournament tournament = findTournament(window.tournamentId());
        VotingWindow.CloseOutcome outcome = window.close(now,
            EliminationAlgorithms.forKind(tournament.algorithm()),
            tournament.eliminationFraction());
        windows.save(window);

        List<TournamentEntry> eliminated = outcome.eliminatedEntryIds().stream()
            .map(this::findEntry)
            .sorted(Comparator.comparing(TournamentEntry::plantId)) // устойчивый порядок (12.3)
            .toList();
        for (TournamentEntry entry : eliminated) {
            entry.eliminate();
            entries.save(entry);
            plantLifecycle.registerDeath(entry.plantId(),
                PlantLifecycleGateway.RestrictionKind.PERMANENT, null,
                "Поражение в раунде " + window.sequence() + " турнира «"
                    + tournament.name() + "»",
                entry.id());
            eligibility.release(entry.reservationId());
            publishEliminated(window, tournament, entry, now);
        }
        if (outcome.winnerEntryId() != null) {
            TournamentEntry winner = findEntry(outcome.winnerEntryId());
            winner.declareWinner();
            entries.save(winner);
            eligibility.release(winner.reservationId());
            tournament.finish(now);
            tournaments.save(tournament);
            publishFinished(tournament, winner, now);
        } else {
            windows.save(VotingWindow.open(tournament.id(), window.sequence() + 1,
                seedsFor(outcome.survivedEntryIds()), now,
                now.plus(tournament.roundDuration()), now));
        }
    }

    private List<VotingWindow.ParticipantSeed> seedsFor(List<UUID> entryIds) {
        return entryIds.stream()
            .map(entryId -> {
                TournamentEntry entry = findEntry(entryId);
                return new VotingWindow.ParticipantSeed(entry.id(), entry.userId(),
                    entry.joinedAt());
            })
            .toList();
    }

    private void publishEliminated(VotingWindow window, Tournament tournament,
                                   TournamentEntry entry, Instant now) {
        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new EntryEliminatedEvent(eventId, EntryEliminatedEvent.TYPE,
            EntryEliminatedEvent.SCHEMA_VERSION, window.id(), window.version(), now, eventId,
            new EntryEliminatedEvent.Payload(tournament.id(), entry.id(), entry.userId(),
                entry.plantId(), window.sequence())));
    }

    private void publishFinished(Tournament tournament, TournamentEntry winner, Instant now) {
        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new TournamentFinishedEvent(eventId, TournamentFinishedEvent.TYPE,
            TournamentFinishedEvent.SCHEMA_VERSION, tournament.id(), tournament.version(),
            now, eventId, new TournamentFinishedEvent.Payload(tournament.id(), winner.id(),
                winner.userId(), winner.plantId())));
    }

    private TournamentEntry findEntry(UUID entryId) {
        return entries.findById(entryId)
            .orElseThrow(() -> new IllegalStateException("Участие не найдено: " + entryId));
    }

    private Tournament findTournament(UUID tournamentId) {
        return tournaments.findById(tournamentId)
            .orElseThrow(() -> new TournamentNotFoundException(
                "Турнир не найден: " + tournamentId));
    }
}
```

- [ ] **Step 5: Первый раунд при старте — красный, затем зелёный**

Добавить в `StartTournamentServiceTest` (существующий; в его setup добавить `InMemoryVotingWindowRepository windows` и передать в конструктор сервиса):

```java
    @Test
    @DisplayName("старт создаёт первый VotingWindow: sequence 1, OPEN, состав = допущенные, счёт 0")
    void старт_создаёт_первое_окно() {
        // ... (существующий сценарий старта с двумя READY)
        TournamentData result = service.start(admin, tournamentId);

        VotingWindow window = windows.findLatestByTournamentId(tournamentId).orElseThrow();
        assertThat(window.sequence()).isEqualTo(1);
        assertThat(window.status()).isEqualTo(WindowStatus.OPEN);
        assertThat(window.opensAt()).isEqualTo(result.createdAt()); // now старта — сверить с полем
        assertThat(window.closesAt()).isEqualTo(window.opensAt().plusSeconds(3600));
        assertThat(window.participants()).hasSize(2);
        assertThat(window.participants()).allSatisfy(p -> assertThat(p.score()).isZero());
    }
```

Запустить — красный:

```bash
./mvnw -q test -Dtest='StartTournamentServiceTest'
```

Ожидание: FAIL — окно не создаётся (репозиторий пуст).

Реализация — модификация `StartTournamentService`:

1. Конструктор: добавить параметр `VotingWindowRepository windows` (поле `windows`).
2. В `doStart` заменить цикл сохранения entries (фрагмент после `tournament.start(now); tournaments.save(tournament);`):

```java
        List<TournamentEntry> admitted = new ArrayList<>();
        for (Invitation invitation : ready) {
            admitted.add(entries.save(TournamentEntry.admit(tournament.id(),
                invitation.userId(), invitation.submittedPlantId(),
                invitation.reservationId(), now)));
        }
        // первый раунд (раздел 7): состав зафиксирован, счёт с нуля
        windows.save(VotingWindow.open(tournament.id(), 1,
            admitted.stream()
                .map(entry -> new VotingWindow.ParticipantSeed(entry.id(), entry.userId(),
                    entry.joinedAt()))
                .toList(),
            now, now.plus(tournament.roundDuration()), now));
```

(старый цикл `for (Invitation invitation : ready) { entries.save(...); }` удаляется; импорты `ArrayList`, `VotingWindow`, `VotingWindowRepository` добавить).

Запустить — зелёный:

```bash
./mvnw -q test -Dtest='StartTournamentServiceTest,CloseVotingWindowServiceTest'
```

Ожидание: PASS.

- [ ] **Step 6: Красный `VotingQueryServiceTest`, затем реализация**

`src/test/java/com/plantarena/tournaments/application/VotingQueryServiceTest.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.GetLeaderboardUseCase;
import com.plantarena.tournaments.application.port.in.ListResultsUseCase;
import com.plantarena.tournaments.application.port.in.ListRoundsUseCase;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.application.support.InMemoryVotingWindowRepository;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingSubject;
import com.plantarena.tournaments.domain.VotingWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Запросы итерации 6: раунды, лидерборд окна, итоги (раздел 13). */
@DisplayName("Раунды, лидерборд и итоги турнира")
class VotingQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations = new InMemoryInvitationRepository();
    private final InMemoryTournamentEntryRepository entries =
        new InMemoryTournamentEntryRepository();
    private final InMemoryVotingWindowRepository windows = new InMemoryVotingWindowRepository();
    private final VotingQueryService service = new VotingQueryService(tournaments, invitations,
        entries, windows, new TournamentsAccessPolicy());

    private UUID tournamentId;
    private UUID entry1;
    private UUID entry2;
    private UUID user1;
    private UUID window1Id;
    private UUID window2Id;

    @BeforeEach
    void setUp() {
        UUID organizer = UUID.randomUUID();
        user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        Instant deadline = NOW.minusSeconds(3600);
        Tournament tournament = Tournament.createDraft(organizer, "Турнир", null,
            deadline, Duration.ofSeconds(60), 0.5, 2, Set.of(), deadline.minusSeconds(600));
        tournament.openRegistration(deadline.minusSeconds(599));
        tournament.start(NOW, 2);
        tournaments.save(tournament);
        tournamentId = tournament.id();
        entry1 = entries.save(TournamentEntry.admit(tournamentId, user1, UUID.randomUUID(),
            UUID.randomUUID(), NOW)).id();
        entry2 = entries.save(TournamentEntry.admit(tournamentId, user2, UUID.randomUUID(),
            UUID.randomUUID(), NOW)).id();

        VotingWindow first = VotingWindow.open(tournamentId, 1, seeds(), NOW.minusSeconds(120),
            NOW.minusSeconds(60), NOW.minusSeconds(120));
        first.castVote(VotingSubject.user(UUID.randomUUID()), entry2, VoteValue.LIKE,
            NOW.minusSeconds(100));
        first.close(NOW.minusSeconds(60),
            new com.plantarena.tournaments.domain.RoundElimination(), 0.5);
        windows.save(first);
        window1Id = first.id();

        VotingWindow second = VotingWindow.open(tournamentId, 2,
            List.copyOf(seeds().stream().limit(1).toList()), // останется один — победитель
            NOW.minusSeconds(60), NOW.plusSeconds(60), NOW.minusSeconds(60));
        windows.save(second);
        window2Id = second.id();
    }

    @Test
    @DisplayName("раунды: список по sequence с total")
    void раунды() {
        var result = service.list(actor(user1), tournamentId, 0, 20);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).sequence()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo("CLOSED");
        assertThat(result.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("лидерборд без windowId — последнее окно; порядок score DESC с позициями")
    void лидерборд_последнего_окна() {
        GetLeaderboardUseCase.LeaderboardResult result =
            service.get(actor(user1), tournamentId, null, 0, 20);
        assertThat(result.windowId()).isEqualTo(window2Id);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).position()).isEqualTo(1);
    }

    @Test
    @DisplayName("лидерборд конкретного окна: счёт и итоги закрытого раунда")
    void лидерборд_конкретного_окна() {
        GetLeaderboardUseCase.LeaderboardResult result =
            service.get(actor(user1), tournamentId, window1Id, 0, 20);
        assertThat(result.items()).extracting("entryId")
            .containsExactly(entry2, entry1); // +1 первый, 0 второй
        assertThat(result.items().get(0).score()).isEqualTo(1L);
        assertThat(result.items().get(1).result()).isEqualTo("ELIMINATED");
    }

    @Test
    @DisplayName("чужое окно (другой турнир) — 404")
    void чужое_окно() {
        assertThatThrownBy(() -> service.get(actor(user1), tournamentId, UUID.randomUUID(),
            0, 20)).isInstanceOf(WindowNotFoundException.class);
    }

    @Test
    @DisplayName("итоги: WINNER первым, eliminatedInRound проставлен")
    void итоги() {
        entries.save(TournamentEntry.restore(entry2, tournamentId, UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(),
            com.plantarena.tournaments.domain.EntryStatus.WINNER, NOW));
        entries.save(TournamentEntry.restore(entry1, tournamentId, UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(),
            com.plantarena.tournaments.domain.EntryStatus.ELIMINATED, NOW));
        ListResultsUseCase.ResultListResult result =
            service.list(actor(user1), tournamentId, 0, 20);
        assertThat(result.winnerEntryId()).isEqualTo(entry2);
        assertThat(result.items().get(0).entryId()).isEqualTo(entry2);
        assertThat(result.items().get(0).status()).isEqualTo("WINNER");
        assertThat(result.items().get(1).eliminatedInRound()).isEqualTo(1);
    }

    @Test
    @DisplayName("посторонний не видит раунды — 404 (турнир скрыт)")
    void посторонний() {
        assertThatThrownBy(() -> service.list(actor(UUID.randomUUID()), tournamentId, 0, 20))
            .isInstanceOf(TournamentNotFoundException.class);
    }

    private List<VotingWindow.ParticipantSeed> seeds() {
        return List.of(
            new VotingWindow.ParticipantSeed(entry1, user1, NOW),
            new VotingWindow.ParticipantSeed(entry2, UUID.randomUUID(), NOW));
    }

    private CurrentActor actor(UUID userId) {
        return new CurrentActor(userId, Set.of(AppRole.USER), false);
    }
}
```

**Пояснение к setup:** во втором окне один участник — это состояние «турнир дошёл до финального окна» эмулируется вручную (в реальном потоке окно с одним участником не создаётся — победитель фиксируется закрытием предыдущего); для запросов это неважно, но чтобы не нарушать инвариант домена, второе окно в тесте создавайте через `VotingWindow.open(...)` с двумя seeds и сразу `close` — либо упростите: одно закрытое окно + `entries` со статусами. Выберите вариант, при котором `VotingWindow.open` не нарушает собственную проверку «минимум 2 участника»: создайте второе окно с двумя seeds и не закрывайте.

Порты (в `application/port/in`):

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** История раундов турнира (раздел 13). */
public interface ListRoundsUseCase {

    RoundListResult list(CurrentActor actor, UUID tournamentId, int page, int size);

    record RoundData(UUID id, int sequence, String status, Instant opensAt, Instant closesAt) {
    }

    record RoundListResult(List<RoundData> items, long total) {
    }
}
```

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Счёт выбранного окна (раздел 13); без windowId — текущее/последнее окно. */
public interface GetLeaderboardUseCase {

    LeaderboardResult get(CurrentActor actor, UUID tournamentId, UUID windowId,
                          int page, int size);

    record LeaderboardEntry(int position, UUID entryId, UUID userId, long score,
                            String result) {
    }

    record LeaderboardResult(UUID windowId, int sequence, String status, Instant closesAt,
                             List<LeaderboardEntry> items, long total) {
    }
}
```

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.List;
import java.util.UUID;

/** Итоги турнира: победитель и выбывшие (раздел 13). */
public interface ListResultsUseCase {

    ResultListResult list(CurrentActor actor, UUID tournamentId, int page, int size);

    record ResultData(UUID entryId, UUID userId, UUID plantId, String status,
                      Integer eliminatedInRound) {
    }

    record ResultListResult(UUID winnerEntryId, List<ResultData> items, long total) {
    }
}
```

`src/main/java/com/plantarena/tournaments/application/VotingQueryService.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.GetLeaderboardUseCase;
import com.plantarena.tournaments.application.port.in.ListResultsUseCase;
import com.plantarena.tournaments.application.port.in.ListRoundsUseCase;
import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.ParticipantRanking;
import com.plantarena.tournaments.domain.ParticipantResult;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import com.plantarena.tournaments.domain.WindowParticipant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Запросы итерации 6 (раздел 13): раунды, лидерборд окна (score DESC,
 * позиции), итоги (WINNER первым, раунд выбывания). Доступ — как к турниру
 * (организатор/админ/активное приглашение/участие), скрытое — 404.
 */
@Service
@Transactional(readOnly = true)
public class VotingQueryService implements ListRoundsUseCase, GetLeaderboardUseCase,
        ListResultsUseCase {

    private static final Set<InvitationStatus> ACTIVE_INVITATION_STATUSES = Set.of(
        InvitationStatus.INVITED, InvitationStatus.ACCEPTED_PENDING_MODERATION,
        InvitationStatus.READY);

    private final TournamentRepository tournaments;
    private final InvitationRepository invitations;
    private final TournamentEntryRepository entries;
    private final VotingWindowRepository windows;
    private final TournamentsAccessPolicy accessPolicy;

    public VotingQueryService(TournamentRepository tournaments,
                              InvitationRepository invitations,
                              TournamentEntryRepository entries,
                              VotingWindowRepository windows,
                              TournamentsAccessPolicy accessPolicy) {
        this.tournaments = tournaments;
        this.invitations = invitations;
        this.entries = entries;
        this.windows = windows;
        this.accessPolicy = accessPolicy;
    }

    @Override
    public RoundListResult list(CurrentActor actor, UUID tournamentId, int page, int size) {
        Tournament tournament = find(tournamentId);
        requireViewer(actor, tournament);
        List<RoundData> items = windows.findByTournamentId(tournamentId, page * size, size)
            .stream()
            .map(window -> new RoundData(window.id(), window.sequence(), window.status().name(),
                window.opensAt(), window.closesAt()))
            .toList();
        return new RoundListResult(items, windows.countByTournamentId(tournamentId));
    }

    @Override
    public LeaderboardResult get(CurrentActor actor, UUID tournamentId, UUID windowId,
                                 int page, int size) {
        Tournament tournament = find(tournamentId);
        requireViewer(actor, tournament);
        VotingWindow window = resolveWindow(tournamentId, windowId);
        List<WindowParticipant> ranked = ParticipantRanking.rank(window.participants());
        List<LeaderboardEntry> items = new ArrayList<>();
        int from = Math.min(page * size, ranked.size());
        int to = Math.min(from + size, ranked.size());
        for (int i = from; i < to; i++) {
            WindowParticipant participant = ranked.get(i);
            items.add(new LeaderboardEntry(i + 1, participant.entryId(), participant.userId(),
                participant.score(), participant.result().name()));
        }
        return new LeaderboardResult(window.id(), window.sequence(), window.status().name(),
            window.closesAt(), items, ranked.size());
    }

    @Override
    public ResultListResult list(CurrentActor actor, UUID tournamentId, int page, int size) {
        Tournament tournament = find(tournamentId);
        requireViewer(actor, tournament);
        Map<UUID, Integer> eliminatedRound = new HashMap<>();
        UUID winnerEntryId = null;
        for (VotingWindow window : windows.findAllByTournamentId(tournamentId)) {
            for (WindowParticipant participant : window.participants()) {
                if (participant.result() == ParticipantResult.ELIMINATED) {
                    eliminatedRound.put(participant.entryId(), window.sequence());
                }
                if (participant.result() == ParticipantResult.WINNER) {
                    winnerEntryId = participant.entryId();
                }
            }
        }
        List<TournamentEntry> all = entries.findByTournamentId(tournamentId, 0,
            Integer.MAX_VALUE);
        List<ResultData> items = all.stream()
            .sorted(Comparator
                .comparing((TournamentEntry entry) ->
                    entry.status() == EntryStatus.WINNER ? 0 : 1)
                .thenComparing(entry -> eliminatedRound.getOrDefault(entry.id(),
                    Integer.MAX_VALUE), Comparator.reverseOrder())
                .thenComparing(TournamentEntry::joinedAt)
                .thenComparing(TournamentEntry::id))
            .skip((long) page * size).limit(size)
            .map(entry -> new ResultData(entry.id(), entry.userId(), entry.plantId(),
                entry.status().name(), eliminatedRound.get(entry.id())))
            .toList();
        return new ResultListResult(winnerEntryId, items, all.size());
    }

    private VotingWindow resolveWindow(UUID tournamentId, UUID windowId) {
        VotingWindow window = windowId == null
            ? windows.findLatestByTournamentId(tournamentId)
                .orElseThrow(() -> new WindowNotFoundException(
                    "У турнира ещё нет окон: " + tournamentId))
            : windows.findById(windowId)
                .orElseThrow(() -> new WindowNotFoundException("Окно не найдено: " + windowId));
        if (!window.tournamentId().equals(tournamentId)) {
            throw new WindowNotFoundException("Окно не принадлежит турниру: " + windowId);
        }
        return window;
    }

    private void requireViewer(CurrentActor actor, Tournament tournament) {
        accessPolicy.requireTournamentViewer(actor, tournament,
            visibleBeyondOrganizer(actor, tournament.id()));
    }

    private boolean visibleBeyondOrganizer(CurrentActor actor, UUID tournamentId) {
        boolean invited = invitations.findByTournamentIdAndUserId(tournamentId, actor.userId())
            .map(invitation -> ACTIVE_INVITATION_STATUSES.contains(invitation.status()))
            .orElse(false);
        return invited || entries.existsByTournamentIdAndUserId(tournamentId, actor.userId());
    }

    private Tournament find(UUID tournamentId) {
        return tournaments.findById(tournamentId)
            .orElseThrow(() -> new TournamentNotFoundException(
                "Турнир не найден: " + tournamentId));
    }
}
```

- [ ] **Step 7: Запустить — зелёный, весь unit-контур**

```bash
./mvnw -q test -Dtest='VotingQueryServiceTest,CloseVotingWindowServiceTest,StartTournamentServiceTest'
./mvnw -q test
```

Ожидание: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/plantarena/tournaments src/test/java/com/plantarena/tournaments
git commit -m "feat(voting): application — закрытие окон, первый раунд при старте, события, запросы"
```

---

### Task 6: Хранилище — миграция V3, JPA-адаптер, контрактные тесты

**Files:**
- Create: `src/main/resources/db/migration/tournaments/V3__voting.sql`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/VotingWindowJpaEntity.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/WindowParticipantJpaEntity.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/VoteJpaEntity.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/VotingWindowJpaRepository.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaVotingWindowRepository.java`
- Modify: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaTournamentEntryRepository.java` (+ Spring Data `findById` — проверить, существует ли)
- Test: `src/test/java/com/plantarena/tournaments/VotingWindowRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/tournaments/application/support/InMemoryVotingWindowRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/tournaments/adapter/out/persistence/JpaVotingWindowRepositoryContractIT.java`
- Test: `src/test/java/com/plantarena/tournaments/TournamentEntryRepositoryContractTest.java` (добавить случай `findById`)

**Interfaces:**
- Consumes: домен Tasks 2–3, паттерн JPA-адаптеров итерации 5 (`TournamentJpaEntity` и др.), `@DataJpaTest`/`@SpringBootTest` с Testcontainers (как `JpaTournamentRepositoryContractIT`).
- Produces (для Task 7): `JpaVotingWindowRepository implements VotingWindowRepository` (Spring-бин `@Repository`); схема БД V3.

- [ ] **Step 1: Миграция V3**

`src/main/resources/db/migration/tournaments/V3__voting.sql`:

```sql
-- Контекст tournaments: окна голосования, участники окон, голоса
-- (разделы 7, 9, 11). Enum → VARCHAR + CHECK; маппинг явный.
-- version (optimistic locking) у конкурентного voting_window.
-- PROMOTED (глобальная квалификация) добавит итерация 7 расширением CHECK.
CREATE TABLE voting_window (
    id            UUID PRIMARY KEY,
    tournament_id UUID        NOT NULL REFERENCES tournament (id),
    sequence      INTEGER     NOT NULL CHECK (sequence >= 1),
    status        VARCHAR(10) NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    opens_at      TIMESTAMPTZ NOT NULL,
    closes_at     TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT voting_window_sequence_uidx UNIQUE (tournament_id, sequence),
    CONSTRAINT voting_window_interval_chk CHECK (opens_at < closes_at)
);
-- не более одного OPEN-окна на турнир (следующее создаётся закрытием предыдущего)
CREATE UNIQUE INDEX voting_window_one_open_uidx ON voting_window (tournament_id) WHERE status = 'OPEN';
CREATE INDEX voting_window_due_idx ON voting_window (status, closes_at);

CREATE TABLE window_participant (
    id        UUID         PRIMARY KEY,
    window_id UUID         NOT NULL REFERENCES voting_window (id),
    entry_id  UUID         NOT NULL,
    user_id   UUID         NOT NULL,
    score     BIGINT       NOT NULL DEFAULT 0,
    result    VARCHAR(15)  NOT NULL CHECK (result IN ('ACTIVE', 'SURVIVED', 'ELIMINATED', 'WINNER')),
    joined_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT window_participant_pair_uidx UNIQUE (window_id, entry_id)
);
CREATE INDEX window_participant_score_idx ON window_participant (window_id, score DESC);

CREATE TABLE vote (
    id                   UUID         PRIMARY KEY,
    window_participant_id UUID        NOT NULL REFERENCES window_participant (id),
    subject_key          VARCHAR(80)  NOT NULL,
    value                VARCHAR(10)  NOT NULL CHECK (value IN ('LIKE', 'DISLIKE')),
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT vote_subject_uidx UNIQUE (window_participant_id, subject_key)
);
CREATE INDEX vote_participant_idx ON vote (window_participant_id);
```

- [ ] **Step 2: Красный контрактный тест**

`src/test/java/com/plantarena/tournaments/VotingWindowRepositoryContractTest.java` (абстрактный, `@Transactional` на классе — урок итерации 2):

```java
package com.plantarena.tournaments;

import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingSubject;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import com.plantarena.tournaments.domain.WindowStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт репозитория VotingWindow: фейк и JPA-адаптер ведут себя одинаково
 * (честность фейка из application-тестов). @Transactional на классе — урок
 * итерации 2.
 */
@Transactional
@DisplayName("Контракт VotingWindowRepository: roundtrip, due, latest")
public abstract class VotingWindowRepositoryContractTest {

    private static final Instant OPENS = Instant.parse("2026-09-27T10:00:00Z");
    private static final Instant CLOSES = OPENS.plusSeconds(60);

    protected abstract VotingWindowRepository repository();

    @Test
    @DisplayName("save + findById: окно с участниками, голосами и счётом восстанавливается")
    void roundtrip_окна() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        UUID entry1 = UUID.randomUUID();
        UUID entry2 = UUID.randomUUID();
        VotingWindow window = VotingWindow.open(UUID.randomUUID(), 1, List.of(
            new VotingWindow.ParticipantSeed(entry1, user1, OPENS),
            new VotingWindow.ParticipantSeed(entry2, user2, OPENS)),
            OPENS, CLOSES, OPENS);
        window.castVote(VotingSubject.user(user1), entry2, VoteValue.LIKE, OPENS);
        window.castVote(VotingSubject.user(user2), entry1, VoteValue.DISLIKE, OPENS);
        repository().save(window);

        VotingWindow restored = repository().findById(window.id()).orElseThrow();

        assertThat(restored.sequence()).isEqualTo(1);
        assertThat(restored.status()).isEqualTo(WindowStatus.OPEN);
        assertThat(restored.participants()).hasSize(2);
        assertThat(restored.scoreOf(entry2)).isEqualTo(1L);
        assertThat(restored.scoreOf(entry1)).isEqualTo(-1L);
        assertThat(restored.myVote(VotingSubject.user(user1).subjectKey(), entry2))
            .isEqualTo(VoteValue.LIKE);
        assertThat(restored.votesOf(entry1)).hasSize(1);
    }

    @Test
    @DisplayName("обновление голоса и удаление сохраняются (смена знака, компенсация)")
    void обновление_голосов() {
        UUID user1 = UUID.randomUUID();
        UUID entry1 = UUID.randomUUID();
        UUID entry2 = UUID.randomUUID();
        VotingWindow window = window(user1, entry1, entry2);
        window.castVote(VotingSubject.user(user1), entry2, VoteValue.LIKE, OPENS);
        repository().save(window);

        VotingWindow loaded = repository().findByIdForUpdate(window.id()).orElseThrow();
        loaded.castVote(VotingSubject.user(user1), entry2, VoteValue.DISLIKE, OPENS);
        repository().save(loaded);
        assertThat(repository().findById(window.id()).orElseThrow().scoreOf(entry2))
            .isEqualTo(-1L);

        VotingWindow again = repository().findByIdForUpdate(window.id()).orElseThrow();
        again.removeVote(VotingSubject.user(user1), entry2, OPENS);
        repository().save(again);
        VotingWindow after = repository().findById(window.id()).orElseThrow();
        assertThat(after.scoreOf(entry2)).isZero();
        assertThat(after.votesOf(entry2)).isEmpty();
    }

    @Test
    @DisplayName("закрытое окно сохраняет результаты участников (история не переписывается)")
    void закрытое_окно() {
        UUID user1 = UUID.randomUUID();
        UUID entry1 = UUID.randomUUID();
        UUID entry2 = UUID.randomUUID();
        VotingWindow window = window(user1, entry1, entry2);
        window.castVote(VotingSubject.user(user1), entry2, VoteValue.LIKE, OPENS);
        window.close(CLOSES, new com.plantarena.tournaments.domain.RoundElimination(), 0.5);
        repository().save(window);

        VotingWindow restored = repository().findById(window.id()).orElseThrow();
        assertThat(restored.status()).isEqualTo(WindowStatus.CLOSED);
        assertThat(restored.scoreOf(entry1)).isEqualTo(0L);
        assertThat(restored.participants()).extracting("result")
            .containsExactlyInAnyOrder("WINNER", "ELIMINATED");
    }

    @Test
    @DisplayName("findDueForClose: только OPEN с closesAt <= now, по closesAt")
    void due_окна() {
        UUID tournamentId = UUID.randomUUID();
        UUID user1 = UUID.randomUUID();
        UUID entry1 = UUID.randomUUID();
        UUID entry2 = UUID.randomUUID();
        VotingWindow due = VotingWindow.open(tournamentId, 1, seeds(entry1, entry2, user1),
            OPENS.minusSeconds(120), OPENS.minusSeconds(60), OPENS.minusSeconds(120));
        VotingWindow future = VotingWindow.open(tournamentId, 2,
            seeds(UUID.randomUUID(), UUID.randomUUID(), user1),
            OPENS, OPENS.plusSeconds(60), OPENS);
        repository().save(due);
        repository().save(future);

        List<UUID> found = repository().findDueForClose(OPENS, 10);

        assertThat(found).containsExactly(due.id());
    }

    @Test
    @DisplayName("findLatestByTournamentId и countByTournamentId")
    void latest_и_count() {
        UUID tournamentId = UUID.randomUUID();
        UUID user1 = UUID.randomUUID();
        VotingWindow first = VotingWindow.open(tournamentId, 1,
            seeds(UUID.randomUUID(), UUID.randomUUID(), user1),
            OPENS.minusSeconds(120), OPENS.minusSeconds(60), OPENS.minusSeconds(120));
        VotingWindow second = VotingWindow.open(tournamentId, 2,
            seeds(UUID.randomUUID(), UUID.randomUUID(), user1),
            OPENS.minusSeconds(60), OPENS.plusSeconds(60), OPENS.minusSeconds(60));
        repository().save(first);
        repository().save(second);

        assertThat(repository().findLatestByTournamentId(tournamentId)).contains(second);
        assertThat(repository().countByTournamentId(tournamentId)).isEqualTo(2);
        assertThat(repository().findAllByTournamentId(tournamentId))
            .extracting(VotingWindow::sequence).containsExactly(1, 2);
    }

    private VotingWindow window(UUID user1, UUID entry1, UUID entry2) {
        return VotingWindow.open(UUID.randomUUID(), 1,
            seeds(entry1, entry2, user1), OPENS, CLOSES, OPENS);
    }

    private List<VotingWindow.ParticipantSeed> seeds(UUID entry1, UUID entry2, UUID user1) {
        return List.of(
            new VotingWindow.ParticipantSeed(entry1, user1, OPENS),
            new VotingWindow.ParticipantSeed(entry2, UUID.randomUUID(), OPENS));
    }
}
```

`src/test/java/com/plantarena/tournaments/application/support/InMemoryVotingWindowRepositoryContractTest.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.VotingWindowRepositoryContractTest;
import com.plantarena.tournaments.domain.VotingWindowRepository;

@org.junit.jupiter.api.DisplayName("In-memory фейк VotingWindowRepository (контракт)")
class InMemoryVotingWindowRepositoryContractTest extends VotingWindowRepositoryContractTest {

    private final InMemoryVotingWindowRepository repository = new InMemoryVotingWindowRepository();

    @Override
    protected VotingWindowRepository repository() {
        return repository;
    }
}
```

`src/test/java/com/plantarena/tournaments/adapter/out/persistence/JpaVotingWindowRepositoryContractIT.java` — по образцу `JpaTournamentRepositoryContractIT` (свериться с его аннотациями: `@SpringBootTest` + Testcontainers или `@DataJpaTest`; tournament для FK создать через `JpaTournamentRepository`/`JdbcTemplate` в `@BeforeEach`):

```java
package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.VotingWindowRepositoryContractTest;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@org.junit.jupiter.api.DisplayName("JPA-адаптер VotingWindowRepository на Testcontainers (контракт)")
@SpringBootTest
class JpaVotingWindowRepositoryContractIT extends VotingWindowRepositoryContractTest {

    @Autowired
    private JpaVotingWindowRepository repository;

    @Override
    protected VotingWindowRepository repository() {
        return repository;
    }
}
```

**Важно:** тесты создают `VotingWindow.open(UUID.randomUUID(), ...)` со случайным `tournamentId`, но миграция требует FK `voting_window.tournament_id → tournament(id)`. Поэтому: (а) в контрактном тесте `tournamentId` создавать через сохранение реального `Tournament` (передать в тест репозиторий турниров или `JdbcTemplate`-вставку), либо (б) в домене оставить FK-проверку на уровне БД и в тесте использовать общий хелпер `createTournamentId()`. Реализуйте вариант (а): абстрактный класс получает `protected abstract UUID newTournamentId();` — in-memory возвращает `UUID.randomUUID()`, JPA-наследник создаёт турнир через `TournamentRepository` (или SQL-вставку минимальной строки `tournament`). Все вызовы `VotingWindow.open(UUID.randomUUID(), ...)` в тесте заменить на `VotingWindow.open(newTournamentId(), ...)`.

Запустить — красный:

```bash
./mvnw -q test -Dtest='InMemoryVotingWindowRepositoryContractTest'
./mvnw -q verify -Dit.test='JpaVotingWindowRepositoryContractIT' -DfailIfNoTests=false
```

Ожидание: FAIL (компиляция): `cannot find symbol: class JpaVotingWindowRepository`.

- [ ] **Step 3: JPA-адаптер**

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/VotingWindowJpaEntity.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** JPA-модель окна голосования (раздел 11); маппинг в домен — явный. */
@Entity
@Table(name = "voting_window")
public class VotingWindowJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tournament_id", nullable = false)
    private UUID tournamentId;

    @Column(name = "sequence", nullable = false)
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
    private long version;

    @OneToMany(mappedBy = "window", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WindowParticipantJpaEntity> participants = new ArrayList<>();

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

    List<WindowParticipantJpaEntity> getParticipants() {
        return participants;
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

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/WindowParticipantJpaEntity.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** JPA-модель участника окна (раздел 11); UNIQUE(window_id, entry_id) в БД. */
@Entity
@Table(name = "window_participant")
public class WindowParticipantJpaEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "window_id")
    private VotingWindowJpaEntity window;

    @Column(name = "entry_id", nullable = false)
    private UUID entryId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private long score;

    @Column(nullable = false)
    private String result;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @OneToMany(mappedBy = "participant", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<VoteJpaEntity> votes = new ArrayList<>();

    UUID getId() {
        return id;
    }

    UUID getEntryId() {
        return entryId;
    }

    UUID getUserId() {
        return userId;
    }

    long getScore() {
        return score;
    }

    String getResult() {
        return result;
    }

    Instant getJoinedAt() {
        return joinedAt;
    }

    List<VoteJpaEntity> getVotes() {
        return votes;
    }

    void setId(UUID id) {
        this.id = id;
    }

    void setWindow(VotingWindowJpaEntity window) {
        this.window = window;
    }

    void setEntryId(UUID entryId) {
        this.entryId = entryId;
    }

    void setUserId(UUID userId) {
        this.userId = userId;
    }

    void setScore(long score) {
        this.score = score;
    }

    void setResult(String result) {
        this.result = result;
    }

    void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/VoteJpaEntity.java`:

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

/** JPA-модель голоса (раздел 11); UNIQUE(window_participant_id, subject_key). */
@Entity
@Table(name = "vote")
public class VoteJpaEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "window_participant_id")
    private WindowParticipantJpaEntity participant;

    @Column(name = "subject_key", nullable = false)
    private String subjectKey;

    @Column(nullable = false)
    private String value;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    UUID getId() {
        return id;
    }

    String getSubjectKey() {
        return subjectKey;
    }

    String getValue() {
        return value;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void setId(UUID id) {
        this.id = id;
    }

    void setParticipant(WindowParticipantJpaEntity participant) {
        this.participant = participant;
    }

    void setSubjectKey(String subjectKey) {
        this.subjectKey = subjectKey;
    }

    void setValue(String value) {
        this.value = value;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/VotingWindowJpaRepository.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data репозиторий окна (раздел 12.1: блокировка PESSIMISTIC_WRITE). */
public interface VotingWindowJpaRepository extends JpaRepository<VotingWindowJpaEntity, UUID> {

    /** SELECT ... FOR UPDATE: сериализует голоса и закрытие одного окна. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from VotingWindowJpaEntity w where w.id = :id")
    Optional<VotingWindowJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("select w.id from VotingWindowJpaEntity w "
        + "where w.status = 'OPEN' and w.closesAt <= :now order by w.closesAt")
    List<UUID> findDueForClose(@Param("now") Instant now, Pageable pageable);

    List<VotingWindowJpaEntity> findByTournamentIdOrderBySequence(UUID tournamentId,
                                                                 Pageable pageable);

    long countByTournamentId(UUID tournamentId);

    Optional<VotingWindowJpaEntity> findFirstByTournamentIdOrderBySequenceDesc(UUID tournamentId);

    List<VotingWindowJpaEntity> findAllByTournamentIdOrderBySequence(UUID tournamentId);
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaVotingWindowRepository.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.Vote;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import com.plantarena.tournaments.domain.WindowParticipant;
import com.plantarena.tournaments.domain.WindowStatus;
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
 * JPA-реализация порта VotingWindowRepository: явный маппинг домена и
 * JPA-модели. Состав окна зафиксирован — участники только добавляются;
 * голоса добавляются/обновляются/удаляются (orphanRemoval). Блокировка —
 * PESSIMISTIC_WRITE (раздел 12.1).
 */
@Repository
public class JpaVotingWindowRepository implements VotingWindowRepository {

    private final VotingWindowJpaRepository jpaRepository;

    public JpaVotingWindowRepository(VotingWindowJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public VotingWindow save(VotingWindow window) {
        VotingWindowJpaEntity entity = jpaRepository.findById(window.id())
            .orElseGet(() -> newEntity(window));
        mapState(entity, window);
        jpaRepository.saveAndFlush(entity);
        return window;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VotingWindow> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional
    public Optional<VotingWindow> findByIdForUpdate(UUID id) {
        return jpaRepository.findByIdForUpdate(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findDueForClose(Instant now, int limit) {
        return jpaRepository.findDueForClose(now, PageRequest.of(0, limit));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VotingWindow> findByTournamentId(UUID tournamentId, int offset, int size) {
        return jpaRepository.findByTournamentIdOrderBySequence(tournamentId,
                PageRequest.of(offset / Math.max(size, 1), Math.max(size, 1)))
            .stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByTournamentId(UUID tournamentId) {
        return jpaRepository.countByTournamentId(tournamentId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VotingWindow> findLatestByTournamentId(UUID tournamentId) {
        return jpaRepository.findFirstByTournamentIdOrderBySequenceDesc(tournamentId)
            .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VotingWindow> findAllByTournamentId(UUID tournamentId) {
        return jpaRepository.findAllByTournamentIdOrderBySequence(tournamentId)
            .stream().map(this::toDomain).toList();
    }

    private VotingWindowJpaEntity newEntity(VotingWindow window) {
        VotingWindowJpaEntity entity = new VotingWindowJpaEntity();
        entity.setId(window.id());
        return entity;
    }

    private void mapState(VotingWindowJpaEntity entity, VotingWindow window) {
        entity.setTournamentId(window.tournamentId());
        entity.setSequence(window.sequence());
        entity.setStatus(window.status().name());
        entity.setOpensAt(window.opensAt());
        entity.setClosesAt(window.closesAt());
        entity.setCreatedAt(window.createdAt());
        for (WindowParticipant participant : window.participants()) {
            WindowParticipantJpaEntity participantEntity = entity.getParticipants().stream()
                .filter(existing -> existing.getEntryId().equals(participant.entryId()))
                .findAny()
                .orElseGet(() -> appendParticipant(entity, participant));
            participantEntity.setScore(participant.score());
            participantEntity.setResult(participant.result().name());
            mapVotes(participantEntity, participant, window);
        }
    }

    private WindowParticipantJpaEntity appendParticipant(VotingWindowJpaEntity entity,
                                                         WindowParticipant participant) {
        WindowParticipantJpaEntity participantEntity = new WindowParticipantJpaEntity();
        participantEntity.setId(participant.id());
        participantEntity.setWindow(entity);
        participantEntity.setEntryId(participant.entryId());
        participantEntity.setUserId(participant.userId());
        participantEntity.setJoinedAt(participant.joinedAt());
        entity.getParticipants().add(participantEntity);
        return participantEntity;
    }

    private void mapVotes(WindowParticipantJpaEntity participantEntity,
                          WindowParticipant participant, VotingWindow window) {
        Set<String> currentKeys = new HashSet<>();
        for (Vote vote : window.votesOf(participant.entryId())) {
            currentKeys.add(vote.subjectKey());
            VoteJpaEntity voteEntity = participantEntity.getVotes().stream()
                .filter(existing -> existing.getSubjectKey().equals(vote.subjectKey()))
                .findAny()
                .orElseGet(() -> appendVote(participantEntity, vote));
            voteEntity.setValue(vote.value().name());
            voteEntity.setUpdatedAt(vote.updatedAt());
        }
        participantEntity.getVotes()
            .removeIf(existing -> !currentKeys.contains(existing.getSubjectKey()));
    }

    private VoteJpaEntity appendVote(WindowParticipantJpaEntity participantEntity, Vote vote) {
        VoteJpaEntity voteEntity = new VoteJpaEntity();
        voteEntity.setId(vote.id());
        voteEntity.setParticipant(participantEntity);
        voteEntity.setSubjectKey(vote.subjectKey());
        voteEntity.setValue(vote.value().name());
        voteEntity.setCreatedAt(vote.createdAt());
        voteEntity.setUpdatedAt(vote.updatedAt());
        participantEntity.getVotes().add(voteEntity);
        return voteEntity;
    }

    private VotingWindow toDomain(VotingWindowJpaEntity entity) {
        List<WindowParticipant> participants = entity.getParticipants().stream()
            .map(this::toDomainParticipant).toList();
        List<Vote> votes = entity.getParticipants().stream()
            .flatMap(participant -> participant.getVotes().stream()
                .map(vote -> toDomainVote(participant.getEntryId(), vote)))
            .toList();
        return VotingWindow.restore(entity.getId(), entity.getTournamentId(),
            entity.getSequence(), WindowStatus.valueOf(entity.getStatus()),
            entity.getOpensAt(), entity.getClosesAt(), entity.getCreatedAt(),
            entity.getVersion(), participants, votes);
    }

    private WindowParticipant toDomainParticipant(WindowParticipantJpaEntity entity) {
        return WindowParticipant.restore(entity.getId(), entity.getEntryId(), entity.getUserId(),
            entity.getScore(), com.plantarena.tournaments.domain.ParticipantResult
                .valueOf(entity.getResult()), entity.getJoinedAt());
    }

    private Vote toDomainVote(UUID entryId, VoteJpaEntity entity) {
        return Vote.restore(entity.getId(), entryId, entity.getSubjectKey(),
            VoteValue.valueOf(entity.getValue()), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
```

`JpaTournamentEntryRepository`: добавить `findById` (маппинг `TournamentEntry.restore(...)` по образцу существующего `save`/`findByTournamentId`), если метода ещё нет. В `TournamentEntryRepositoryContractTest` добавить случай: `findById` возвращает сохранённое участие.

- [ ] **Step 4: Запустить — зелёный**

```bash
./mvnw -q test -Dtest='InMemoryVotingWindowRepositoryContractTest'
./mvnw -q verify -Dit.test='JpaVotingWindowRepositoryContractIT' -DfailIfNoTests=false
```

Ожидание: PASS (5 контрактных сценариев в обоих наследниках). Затем полный прогон:

```bash
./mvnw -q verify
```

Ожидание: PASS — миграция V3 применяется, `ddl-auto=validate` проходит, существующие IT зелёные.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/tournaments/V3__voting.sql \
  src/main/java/com/plantarena/tournaments/adapter/out/persistence \
  src/test/java/com/plantarena/tournaments
git commit -m "feat(voting): хранилище — миграция V3, JPA-адаптер с FOR UPDATE, контрактные тесты"
```

---

### Task 7: Связка — ACL `PlantLifecycle`, poller, demo-ручка, REST; `VotingApiIT` зелёный

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/plants/InProcessPlantLifecycle.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/jobs/VotingWindowClosePoller.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/VotingController.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/VoteRequest.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/VoteResponse.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/MyVoteResponse.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/RoundResponse.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/LeaderboardResponse.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/ResultResponse.java`
- Modify: `src/main/java/com/plantarena/tournaments/adapter/in/web/TournamentController.java` (rounds/leaderboard/results)
- Modify: `src/main/java/com/plantarena/tournaments/adapter/in/web/DemoJobsController.java` (+closedWindows)
- Modify: `src/main/java/com/plantarena/tournaments/adapter/in/web/TournamentsExceptionHandler.java` (новые коды)
- Test: `src/test/java/com/plantarena/tournaments/VotingApiIT.java` (зелёный)

**Interfaces:**
- Consumes: `VotingUseCase`, `CloseVotingWindowUseCase`, `ListRoundsUseCase`, `GetLeaderboardUseCase`, `ListResultsUseCase` (Tasks 4–5), `plants.api.PlantLifecycle`, `PaginationParams`, `CurrentActorProvider`.
- Produces: REST-ручки раздела 13 (см. ниже); poller закрытия; demo-ручка `{"processed": n, "closedWindows": m}`.

- [ ] **Step 1: ACL и poller**

`src/main/java/com/plantarena/tournaments/adapter/out/plants/InProcessPlantLifecycle.java`:

```java
package com.plantarena.tournaments.adapter.out.plants;

import com.plantarena.plants.api.PlantLifecycle;
import com.plantarena.tournaments.application.port.out.PlantLifecycleGateway;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL (раздел 4.3): порт гибели tournaments → опубликованный контракт
 * plants.api.PlantLifecycle. В лабе №2 заменяется на сетевой адаптер с
 * повтором (идемпотентность гибели — plants).
 */
@Component
public class InProcessPlantLifecycle implements PlantLifecycleGateway {

    private final PlantLifecycle plantLifecycle;

    public InProcessPlantLifecycle(PlantLifecycle plantLifecycle) {
        this.plantLifecycle = plantLifecycle;
    }

    @Override
    public void registerDeath(UUID plantId, RestrictionKind kind, Instant cooldownExpiresAt,
                              String reason, UUID sourceEntryId) {
        plantLifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.valueOf(kind.name()),
            cooldownExpiresAt, reason, sourceEntryId);
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/jobs/VotingWindowClosePoller.java`:

```java
package com.plantarena.tournaments.adapter.in.jobs;

import com.plantarena.tournaments.application.port.in.CloseVotingWindowUseCase;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Опрос просроченных окон: fixedDelay 2с, пачка ≤ 10. Тот же use case, что
 * demo-ручка (разделы 7, 12.3); per-window tx и устойчивость к сбоям решает
 * closeDue. Планировщик уже включён (ModerationWiringConfig).
 */
@Component
public class VotingWindowClosePoller {

    private static final Logger log = LoggerFactory.getLogger(VotingWindowClosePoller.class);
    private static final int BATCH_SIZE = 10;

    private final CloseVotingWindowUseCase closeVotingWindow;
    private final Clock clock;

    public VotingWindowClosePoller(CloseVotingWindowUseCase closeVotingWindow, Clock clock) {
        this.closeVotingWindow = closeVotingWindow;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        try {
            closeVotingWindow.closeDue(clock.instant(), BATCH_SIZE);
        } catch (RuntimeException e) {
            log.error("Цикл закрытия окон не удался (продолжаем): {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Demo-ручка — расширить**

`DemoJobsController`: добавить зависимость `CloseVotingWindowUseCase closeVotingWindow` и изменить `run-due`:

```java
    @PostMapping("/run-due")
    @Operation(operationId = "demo-run-due-jobs",
        summary = "Обработать наступившие дедлайны турниров и окон (dev/test, M/A)")
    public Map<String, Integer> runDue() {
        CurrentActor actor = currentActorProvider.currentActor();
        accessPolicy.requireModeratorOrAdmin(actor);
        return Map.of(
            "processed", startTournament.startDue(clock.instant(), 10),
            "closedWindows", closeVotingWindow.closeDue(clock.instant(), 10));
    }
```

- [ ] **Step 3: REST — голосование**

`src/main/java/com/plantarena/tournaments/adapter/in/web/VoteRequest.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Тело PUT vote (раздел 9). */
public record VoteRequest(
        @NotBlank
        @Pattern(regexp = "LIKE|DISLIKE", message = "допустимо только LIKE или DISLIKE")
        String value) {
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/VoteResponse.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

/** Ответ PUT vote: новый счёт участника окна (раздел 9). */
public record VoteResponse(long score) {
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/MyVoteResponse.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

/** Ответ my-vote: текущее значение или null (раздел 13). */
public record MyVoteResponse(String value) {
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/VotingController.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.tournaments.application.port.in.VotingUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Голосование (раздел 13): PUT устанавливает значение (200 + новый score),
 * DELETE удаляет (204, идемпотентно), my-vote возвращает текущее значение.
 * Права и состояние окна решает use case (401/403/404/409).
 */
@RestController
@RequestMapping("/api/v1/windows")
@Tag(name = "voting")
public class VotingController {

    private final VotingUseCase voting;
    private final CurrentActorProvider currentActorProvider;

    public VotingController(VotingUseCase voting, CurrentActorProvider currentActorProvider) {
        this.voting = voting;
        this.currentActorProvider = currentActorProvider;
    }

    @PutMapping("/{windowId}/entries/{entryId}/vote")
    @Operation(operationId = "voting-cast-vote",
        summary = "Установить голос LIKE/DISLIKE за участника окна (участник турнира, не сам)")
    public ResponseEntity<VoteResponse> cast(@PathVariable UUID windowId,
                                             @PathVariable UUID entryId,
                                             @Valid @RequestBody VoteRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        long score = voting.cast(actor, windowId, entryId, request.value());
        return ResponseEntity.ok(new VoteResponse(score));
    }

    @DeleteMapping("/{windowId}/entries/{entryId}/vote")
    @Operation(operationId = "voting-delete-vote",
        summary = "Удалить свой голос до закрытия окна (204, идемпотентно)")
    public ResponseEntity<Void> remove(@PathVariable UUID windowId, @PathVariable UUID entryId) {
        CurrentActor actor = currentActorProvider.currentActor();
        voting.remove(actor, windowId, entryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{windowId}/entries/{entryId}/my-vote")
    @Operation(operationId = "voting-my-vote",
        summary = "Текущий голос субъекта за участника (value или null)")
    public ResponseEntity<MyVoteResponse> myVote(@PathVariable UUID windowId,
                                                 @PathVariable UUID entryId) {
        CurrentActor actor = currentActorProvider.currentActor();
        return ResponseEntity.ok(
            new MyVoteResponse(voting.myVote(actor, windowId, entryId).orElse(null)));
    }
}
```

- [ ] **Step 4: REST — раунды, лидерборд, итоги**

Response DTO:

```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.application.port.in.ListRoundsUseCase;
import java.time.Instant;
import java.util.UUID;

/** Раунд турнира (раздел 13). */
public record RoundResponse(UUID id, int sequence, String status, Instant opensAt,
                            Instant closesAt) {

    static RoundResponse from(ListRoundsUseCase.RoundData data) {
        return new RoundResponse(data.id(), data.sequence(), data.status(), data.opensAt(),
            data.closesAt());
    }
}
```

```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.application.port.in.GetLeaderboardUseCase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Лидерборд окна (раздел 13): счёт выбранного окна, позиции, итоги. */
public record LeaderboardResponse(UUID windowId, int sequence, String status, Instant closesAt,
                                   List<Item> items) {

    static LeaderboardResponse from(GetLeaderboardUseCase.LeaderboardResult result) {
        return new LeaderboardResponse(result.windowId(), result.sequence(), result.status(),
            result.closesAt(),
            result.items().stream()
                .map(item -> new Item(item.position(), item.entryId(), item.userId(),
                    item.score(), item.result()))
                .toList());
    }

    record Item(int position, UUID entryId, UUID userId, long score, String result) {
    }
}
```

```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.application.port.in.ListResultsUseCase;
import java.util.List;
import java.util.UUID;

/** Итоги турнира (раздел 13): победитель и выбывшие с раундом выбывания. */
public record ResultResponse(UUID winnerEntryId, List<Item> items) {

    static ResultResponse from(ListResultsUseCase.ResultListResult result) {
        return new ResultResponse(result.winnerEntryId(),
            result.items().stream()
                .map(item -> new Item(item.entryId(), item.userId(), item.plantId(),
                    item.status(), item.eliminatedInRound()))
                .toList());
    }

    record Item(UUID entryId, UUID userId, UUID plantId, String status,
                Integer eliminatedInRound) {
    }
}
```

`TournamentController` — добавить зависимости `ListRoundsUseCase listRounds`, `GetLeaderboardUseCase getLeaderboard`, `ListResultsUseCase listResults` (конструктор) и три ручки:

```java
    @GetMapping("/{id}/rounds")
    @Operation(operationId = "tournaments-list-rounds",
        summary = "История раундов турнира (X-Total-Count)")
    public ResponseEntity<List<RoundResponse>> rounds(@PathVariable UUID id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        ListRoundsUseCase.RoundListResult result =
            listRounds.list(actor, id, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(result.items().stream().map(RoundResponse::from).toList());
    }

    @GetMapping("/{id}/leaderboard")
    @Operation(operationId = "tournaments-get-leaderboard",
        summary = "Счёт окна (без windowId — текущее/последнее; X-Total-Count)")
    public ResponseEntity<LeaderboardResponse> leaderboard(@PathVariable UUID id,
            @RequestParam(required = false) UUID windowId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        GetLeaderboardUseCase.LeaderboardResult result =
            getLeaderboard.get(actor, id, windowId, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(LeaderboardResponse.from(result));
    }

    @GetMapping("/{id}/results")
    @Operation(operationId = "tournaments-list-results",
        summary = "Итоги: победитель и выбывшие (X-Total-Count)")
    public ResponseEntity<ResultResponse> results(@PathVariable UUID id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        ListResultsUseCase.ResultListResult result =
            listResults.list(actor, id, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(ResultResponse.from(result));
    }
```

`TournamentsExceptionHandler` — добавить обработчики (по образцу существующих):

```java
    @ExceptionHandler(WindowNotFoundException.class)
    public ResponseEntity<ApiError> windowNotFound(WindowNotFoundException e,
                                                   HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "WINDOW_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(EntryNotInWindowException.class)
    public ResponseEntity<ApiError> entryNotInWindow(EntryNotInWindowException e,
                                                     HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "ENTRY_NOT_IN_WINDOW", e.getMessage(), request);
    }

    @ExceptionHandler(VotingClosedException.class)
    public ResponseEntity<ApiError> votingClosed(VotingClosedException e,
                                                 HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "VOTING_CLOSED", e.getMessage(), request);
    }

    @ExceptionHandler(SelfVoteForbiddenException.class)
    public ResponseEntity<ApiError> selfVoteForbidden(SelfVoteForbiddenException e,
                                                      HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "SELF_VOTE_FORBIDDEN", e.getMessage(), request);
    }

    @ExceptionHandler(UnknownVoteValueException.class)
    public ResponseEntity<ApiError> unknownVoteValue(UnknownVoteValueException e,
                                                     HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "UNKNOWN_VOTE_VALUE", e.getMessage(), request);
    }
```

- [ ] **Step 5: `VotingApiIT` зелёный**

```bash
./mvnw -q verify -Dit.test=VotingApiIT -DfailIfNoTests=false
```

Ожидание: PASS — полный цикл (голоса → дельты → закрытие → гибель/запрет → победитель → FINISHED) проходит через HTTP. Затем весь прогон:

```bash
./mvnw -q verify
```

Ожидание: PASS (unit + application + контрактные + все IT + ArchUnit + JaCoCo gate).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/plantarena/tournaments/adapter \
  src/test/java/com/plantarena/tournaments/VotingApiIT.java
git commit -m "feat(voting): связка — ACL PlantLifecycle, poller, demo-ручка, REST; VotingApiIT зелёный"
```

---

### Task 8: Конкурентные тесты — гонки голосов и закрытия

**Files:**
- Test: `src/test/java/com/plantarena/tournaments/VotingConcurrencyIT.java`

**Interfaces:**
- Consumes: `VotingUseCase`, `CloseVotingWindowUseCase` (бины), REST итераций 1–5 (setup через HTTP), реальный PostgreSQL (Testcontainers), `JdbcTemplate`.
- Produces: конкурентные проверки раздела 14.2 (инварианты БД, не только отсутствие исключений).

- [ ] **Step 1: `VotingConcurrencyIT`**

`src/test/java/com/plantarena/tournaments/VotingConcurrencyIT.java`:

```java
package com.plantarena.tournaments;

import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.support.DeterministicPlantClassifier;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.CloseVotingWindowUseCase;
import com.plantarena.tournaments.application.port.in.VotingUseCase;
import com.plantarena.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Конкурентные тесты раздела 14.2: одновременные голоса, гонка голоса и
 * закрытия, повторное закрытие. Реальный PostgreSQL, несколько потоков,
 * CountDownLatch; проверяются инварианты БД (score = сумма голосов, одна
 * гибель, один следующий раунд), а не только отсутствие исключений.
 * Scheduler (2 с) может закрыть окно параллельно — ассерты устойчивы к
 * этому (идемпотентность closeDue).
 */
@DisplayName("Конкурентность: голоса, гонка с закрытием, повторное закрытие")
class VotingConcurrencyIT extends AbstractIntegrationTest {

    private static final String DEMO_HEADER = "X-Demo-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private VotingUseCase voting;

    @Autowired
    private CloseVotingWindowUseCase closeVotingWindow;

    @TestConfiguration
    static class DeterministicClassifierConfig {

        @Bean
        @Primary
        PlantClassifier deterministicPlantClassifier() {
            return new DeterministicPlantClassifier();
        }
    }

    @Test
    @DisplayName("параллельные голоса разных субъектов: score = сумма вкладов, по одному голосу на субъекта")
    void параллельные_голоса() throws Exception {
        Setup setup = setup(60); // окно на 60 с — закрытие не мешает
        int threads = 6; // 3 участника × 2 чужих entry
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> jobs = new ArrayList<>();
        for (UUID voter : List.of(setup.user1, setup.user2, setup.user3)) {
            for (UUID target : List.of(setup.entry1, setup.entry2, setup.entry3)) {
                if (target.equals(entryOf(setup, voter))) {
                    continue; // самоголосование запрещено
                }
                jobs.add(pool.submit(() -> {
                    start.await();
                    voting.cast(actor(voter), setup.windowId, target, "LIKE");
                    ok.incrementAndGet();
                    return null;
                }));
            }
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(ok.get()).isEqualTo(threads);

        // инвариант: score каждого участника = сумма вкладов его голосов в БД
        for (UUID entry : List.of(setup.entry1, setup.entry2, setup.entry3)) {
            Long score = jdbcTemplate.queryForObject(
                "select p.score from tournaments.window_participant p "
                    + "join tournaments.voting_window w on w.id = p.window_id "
                    + "where w.id = ? and p.entry_id = ?",
                Long.class, setup.windowId, entry);
            Long sum = jdbcTemplate.queryForObject(
                "select coalesce(sum(case v.value when 'LIKE' then 1 else -1 end), 0) "
                    + "from tournaments.vote v "
                    + "join tournaments.window_participant p on p.id = v.window_participant_id "
                    + "where p.window_id = ? and p.entry_id = ?",
                Long.class, setup.windowId, entry);
            assertThat(score).as("score = сумма голосов").isEqualTo(sum).isEqualTo(2L);
        }
    }

    @Test
    @DisplayName("гонка голоса и закрытия: голос либо применён и учтён, либо 409; окно закрывается один раз")
    void гонка_голоса_и_закрытия() throws Exception {
        Setup setup = setup(3); // окно на 3 с
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<?> voter = pool.submit(() -> {
            start.await();
            try {
                voting.cast(actor(setup.user1), setup.windowId, setup.entry2, "LIKE");
                accepted.incrementAndGet();
            } catch (com.plantarena.tournaments.application.VotingClosedException e) {
                rejected.incrementAndGet();
            }
            return null;
        });
        Future<?> closer = pool.submit(() -> {
            start.await();
            closeVotingWindow.closeDue(clockPlus(4), 10);
            return null;
        });
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        voter.get();
        closer.get();

        // окно закрыто ровно один раз; голос либо учтён, либо отклонён
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(statusOf(setup.windowId)).isEqualTo("CLOSED"));
        assertThat(accepted.get() + rejected.get()).isEqualTo(1);
        Long sum = jdbcTemplate.queryForObject(
            "select coalesce(sum(case v.value when 'LIKE' then 1 else -1 end), 0) "
                + "from tournaments.vote v "
                + "join tournaments.window_participant p on p.id = v.window_participant_id "
                + "where p.window_id = ? and p.entry_id = ?",
            Long.class, setup.windowId, setup.entry2);
        Long score = jdbcTemplate.queryForObject(
            "select p.score from tournaments.window_participant p where p.window_id = ? "
                + "and p.entry_id = ?",
            Long.class, setup.windowId, setup.entry2);
        assertThat(score).isEqualTo(sum); // инвариант при любой исходе гонки
        if (accepted.get() == 1) {
            assertThat(score).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("повторное закрытие: одна гибель, один запрет, один следующий раунд")
    void повторное_закрытие() throws Exception {
        Setup setup = setup(1); // окно на 1 с
        Awaitility.await().atMost(Duration.ofSeconds(10))
            .until(() -> !closeVotingWindow.closeDue(clockPlus(2), 10).equals(0)
                || statusOf(setup.windowId).equals("CLOSED"));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                start.await();
                closeVotingWindow.closeDue(clockPlus(2), 10);
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // инварианты: окно закрыто, выбывших столько, сколько должен алгоритм,
        // следующий раунд ровно один, растение погибло с одним запретом
        assertThat(statusOf(setup.windowId)).isEqualTo("CLOSED");
        Integer eliminated = jdbcTemplate.queryForObject(
            "select count(*) from tournaments.tournament_entry where tournament_id = ? "
                + "and status = 'ELIMINATED'",
            Integer.class, setup.tournamentId);
        Integer windows = jdbcTemplate.queryForObject(
            "select count(*) from tournaments.voting_window where tournament_id = ?",
            Integer.class, setup.tournamentId);
        Integer restrictions = jdbcTemplate.queryForObject(
            "select count(*) from plants.image_restriction where owner_id = ?",
            Integer.class, eliminatedOwner(setup));
        assertThat(eliminated).isEqualTo(1); // 3 участника, f=0.5 → 1
        assertThat(windows).isEqualTo(2);    // закрытое + следующий раунд
        assertThat(restrictions).isEqualTo(1); // без дубля запрета
    }

    // ---------- setup ----------

    private record Setup(UUID tournamentId, UUID windowId, UUID entry1, UUID entry2, UUID entry3,
                         UUID user1, UUID user2, UUID user3, UUID plant1) {
    }

    private Setup setup(long roundDurationSeconds) throws Exception {
        UUID organizer = adminId();
        UUID u1 = createUserAsAdmin("conc-u1@example.com", "C1");
        UUID u2 = createUserAsAdmin("conc-u2@example.com", "C2");
        UUID u3 = createUserAsAdmin("conc-u3@example.com", "C3");
        UUID tournamentId = createTournamentAsAdmin("Конкурентный",
            Instant.now().plusSeconds(4), roundDurationSeconds);
        inviteAsAdmin(tournamentId, u1);
        inviteAsAdmin(tournamentId, u2);
        inviteAsAdmin(tournamentId, u3);
        mockMvc.perform(post("/api/v1/tournaments/" + tournamentId + "/open-registration")
                .header(DEMO_HEADER, organizer.toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isOk());
        UUID plant1 = greenPlantOf(u1);
        acceptInvitation(u1, tournamentId, plant1);
        acceptInvitation(u2, tournamentId, greenPlantOf(u2));
        acceptInvitation(u3, tournamentId, greenPlantOf(u3));
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> assertThat(tournamentStatus(tournamentId)).isEqualTo("RUNNING"));

        String rounds = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/v1/tournaments/" + tournamentId + "/rounds")
                    .header(DEMO_HEADER, organizer.toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isOk())
            .andReturn().getResponse().getContentAsString();
        UUID windowId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(rounds, "$[0].id"));
        UUID entry1 = entryIdOf(tournamentId, u1);
        UUID entry2 = entryIdOf(tournamentId, u2);
        UUID entry3 = entryIdOf(tournamentId, u3);
        return new Setup(tournamentId, windowId, entry1, entry2, entry3, u1, u2, u3, plant1);
    }

    private UUID entryIdOf(UUID tournamentId, UUID userId) {
        return jdbcTemplate.queryForObject(
            "select id from tournaments.tournament_entry where tournament_id = ? and user_id = ?",
            UUID.class, tournamentId, userId);
    }

    private UUID entryOf(Setup setup, UUID userId) {
        if (userId.equals(setup.user1)) {
            return setup.entry1;
        }
        return userId.equals(setup.user2) ? setup.entry2 : setup.entry3;
    }

    private String statusOf(UUID windowId) {
        return jdbcTemplate.queryForObject(
            "select status from tournaments.voting_window where id = ?",
            String.class, windowId);
    }

    private String tournamentStatus(UUID tournamentId) {
        return jdbcTemplate.queryForObject(
            "select status from tournaments.tournament where id = ?",
            String.class, tournamentId);
    }

    private UUID eliminatedOwner(Setup setup) {
        return jdbcTemplate.queryForObject(
            "select user_id from tournaments.tournament_entry where tournament_id = ? "
                + "and status = 'ELIMINATED'",
            UUID.class, setup.tournamentId);
    }

    /** now из Clock приложения + сдвиг (окна закрываются по closesAt, не по wall-clock теста). */
    private Instant clockPlus(long seconds) {
        return Instant.now().plusSeconds(seconds);
    }

    private CurrentActor actor(UUID userId) {
        return new CurrentActor(userId, Set.of(AppRole.USER), false);
    }

    // ---------- HTTP-хелперы (по образцу VotingApiIT) ----------

    private UUID createTournamentAsAdmin(String name, Instant deadline,
                                         long roundDurationSeconds) throws Exception {
        String body = mockMvc.perform(post("/api/v1/tournaments")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"%s","description":"Описание","registrationDeadline":"%s",\
                    "roundDurationSeconds":%d,"eliminationFraction":0.5,\
                    "minParticipants":2,"tagIds":[]}
                    """.formatted(name, deadline, roundDurationSeconds)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
    }

    private void inviteAsAdmin(UUID tournamentId, UUID userId) throws Exception {
        mockMvc.perform(post("/api/v1/tournaments/" + tournamentId + "/invitations")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\"}".formatted(userId)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isCreated());
    }

    private void acceptInvitation(UUID user, UUID tournamentId, UUID plantId) throws Exception {
        String body = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/v1/me/invitations")
                    .header(DEMO_HEADER, user.toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<?> items = com.jayway.jsonpath.JsonPath.read(body, "$[?(@.status=='INVITED')]");
        UUID invitationId = UUID.fromString(
            (String) ((java.util.Map<String, Object>) items.get(0)).get("id"));
        mockMvc.perform(post("/api/v1/invitations/" + invitationId + "/accept")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plantId)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isOk());
    }

    private UUID greenPlantOf(UUID user) throws Exception {
        UUID assetId = uploadAs(user, referenceBytes("green-8x8.png"));
        String body = mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":\"%s\",\"title\":\"Конкурентный фикус\"}"
                    .formatted(assetId)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
    }

    private UUID uploadAs(UUID userId, byte[] content) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("file", "reference.png",
                    MediaType.IMAGE_PNG_VALUE, content))
                .header(DEMO_HEADER, userId.toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.id"));
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
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(response, "$.id"));
    }
}
```

**Пояснения к ассертам:**
- `clockPlus` использует `Instant.now()` — в IT-профиле `Clock` приложения идёт от системного времени (фиксированный Clock только в unit-тестах), поэтому due-параметр `closeDue` честно «наступает»; scheduler может закрыть окно первым — ассерты это допускают (проверяется инвариант, а не исполнитель).
- В `гонка_голоса_и_закрытия` голос и закрытие стартуют одновременно по `CountDownLatch`; блокировка окна гарантирует: либо голос коммитится до закрытия (учтён в счёте), либо получает `VotingClosedException`.
- В `повторное_закрытие` проверяется отсутствие дубля запрета (`plants.image_restriction` — одна запись на владельца выбывшего) и ровно один следующий раунд (`voting_window` = 2).

- [ ] **Step 2: Запустить**

```bash
./mvnw -q verify -Dit.test=VotingConcurrencyIT -DfailIfNoTests=false
```

Ожидание: PASS. Если гонка нестабильна (flaky) — не ослаблять ассерты, а усилить синхронизацию (например, закрытие вызывать строго после `closesAt` через Awaitility по времени БД).

- [ ] **Step 3: Полный прогон и commit**

```bash
./mvnw -q verify
```

Ожидание: PASS (JaCoCo gate ≥ 70% не сломан).

```bash
git add src/test/java/com/plantarena/tournaments/VotingConcurrencyIT.java
git commit -m "test(voting): конкурентные гонки голосов и закрытия, инварианты БД"
```

---

### Task 9: Документация — ADR-011, глоссарий, агрегаты, context map, README; финальная проверка и merge

**Files:**
- Create: `docs/domain/adr/ADR-011-voting-window-close-tx.md`
- Modify: `docs/domain/glossary.md`
- Modify: `docs/domain/aggregates.md`
- Modify: `docs/domain/context-map.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: всё, реализованное в Tasks 1–8.
- Produces: документация, соответствующая разделам 0.4/17 (сначала docs, затем код — замыкается проверкой).

- [ ] **Step 1: ADR-011**

`docs/domain/adr/ADR-011-voting-window-close-tx.md`:

```markdown
# ADR-011: Закрытие окна голосования — межконтекстная транзакция и идемпотентность

Статус: принято (итерация 6). Контекст: tournaments → plants (разделы 12.3, 4.3).

## Решение

Закрытие окна — один межконтекстный процесс в одной транзакции монолита:

1. `SELECT ... FOR UPDATE` строки `voting_window` (та же блокировка, что у
   голосования — раздел 12.1); повтор для CLOSED — no-op (идемпотентность
   повтора по `(windowId, sequence)`).
2. Закрытие доменом: рейтинг `ParticipantRanking`, выбывание
   `RoundElimination`, результаты участников.
3. `TournamentEntry.eliminate/declareWinner` + `Tournament.finish`.
4. Команды plants для выбывших в устойчивом порядке по `plantId`:
   `PlantLifecycle.registerDeath(PERMANENT, sourceEntryId)` (гибель + запрет,
   идемпотентна на стороне plants) и `PlantEligibility.release` (резерв).
5. Победитель: освобождение резерва, `TournamentFinished`; иначе — следующий
   `VotingWindow` (sequence + 1, выжившие, счёт с нуля).
6. События `EntryEliminated`/`TournamentFinished` публикуются в той же tx.

Голосование — одна tx, один агрегат `VotingWindow` (раздел 12.1), порядок
блокировок окно → window_participant → vote; проверка времени после
блокировки. Ограничение throughput (сериализация голосов одного окна)
признаётся: учебный масштаб.

## Почему

Атомарность «итог окна + гибель + запрет + резервы + следующий раунд» —
обязательное последствие (раздел 10.3), а не побочный эффект: частичное
применение оставило бы выбывшее растение живым или живой резерв у погибшего.

## Только в монолите

В лабе №2 процесс становится saga: локально фиксируются окно и итоги
(ELIMINATED немедленно исключает из голосования/ленты), гибель доставляется
надёжной командой с повтором (идемпотентность — DEAD-статус plants),
резерв держится до подтверждения гибели, компенсация — release orphan-резервов.
В лабе №4 — outbox/inbox (`tournament.lifecycle.v1`, `plant.lifecycle.v1`).
```

- [ ] **Step 2: Глоссарий, агрегаты, context map**

`docs/domain/glossary.md` — добавить строку (после «Субъект голосования»):

```markdown
| Дельта голоса | `VoteValue.transitionDelta` | tournaments | Изменение счёта при переходе голоса: +1/−1 новый, ±2 смена знака, 0 повтор, удаление — компенсация |
```

`docs/domain/aggregates.md` — заменить строку `VotingWindow` (сейчас помечена «(итерация 6)») на фактическую таблицу инвариантов и защитивших их тестов (по образцу строк итераций 1–5): корень `VotingWindow`, состав `WindowParticipant`/`Vote`, инварианты `[opensAt, closesAt)`; состав зафиксирован; score = сумма текущих голосов; один голос субъекта за участника; самоголосование запрещено; повторное закрытие не меняет результатов; команды `проголосовать/изменить/удалить голос/закрыть окно`; тесты `VotingWindowTest`, `VoteValueTest`, `VotingConcurrencyIT`. Также обновить строки `Tournament` (FINISHED) и `TournamentEntry` (ACTIVE → ELIMINATED/WINNER) и убрать пометки «итерация 6» у `EliminationAlgorithm`/`ParticipantRanking` (теперь реализованы: `RoundElimination`, `ParticipantRanking`).

`docs/domain/context-map.md` — в таблицу взаимодействий tournaments → plants добавить команду `PlantLifecycle.registerDeath` (закрытие окна, синхронная, та же tx монолита — ADR-011; в лабе №2 — надёжная команда с повтором) и события `EntryEliminated`/`TournamentFinished` в перечень опубликованных событий tournaments.

- [ ] **Step 3: README**

Обновить `README.md`:

- Раздел «Турниры (tournaments)»: заменить строку «Окна голосования, выбывание, FINISHED — итерация 6» на фактическое поведение: старт создаёт первый раунд `VotingWindow` (roundDuration, счёт с нуля); закрытие по дедлайну — scheduler (2с) или demo-ручка; выбывание `min(n−1, max(1, floor(n·f)))` худших по рейтингу score DESC, joinedAt ASC, entryId ASC; выбывшие — гибель + PERMANENT-запрет + освобождение резерва; один выживший — WINNER, турнир FINISHED, резерв победителя освобождён.
- Новый раздел «Голосование (voting)»: `PUT/DELETE /api/v1/windows/{windowId}/entries/{entryId}/vote`, `GET .../my-vote` — участник, допущенный к старту (выбывший — тоже, допущение 9), самоголосование запрещено, интервал `[opensAt, closesAt)`; дельты +1/−1/±2; `GET /tournaments/{id}/rounds|leaderboard|results`.

- [ ] **Step 4: Финальная проверка**

```bash
./mvnw verify
```

Ожидание: BUILD SUCCESS — unit, application, контрактные, интеграционные (включая `VotingApiIT`, `VotingConcurrencyIT`, `TournamentsApiIT`), ArchUnit, JaCoCo LINE ≥ 70%. Затем smoke через Docker:

```bash
docker compose up --build
# Swagger UI: http://localhost:8080/swagger-ui/index.html — сценарий голосования проходим вручную
docker compose down
```

- [ ] **Step 5: Merge**

```bash
git checkout main
git merge --no-ff feat/iteration-6-voting -m "merge: итерация 6 — голосование и закрытие раундов"
./mvnw -q verify   # main зелёный
```

Push в удалённый `main` — только по отдельной команде пользователя.

---

## Self-Review (выполнен при составлении)

- **Покрытие требований:** раздел 9 (голоса: PUT/DELETE/my-vote, дельты, уникальность, score-инвариант) — Tasks 2, 4, 7, 8; раздел 12.1 (голосование, блокировки, порядок) — Tasks 2, 4, 6, 8; раздел 12.3 (закрытие, идемпотентность, устойчивый порядок, гибель/запреты/резервы) — Tasks 3, 5, 8, ADR-011; раздел 7 (выбывание, WINNER, FINISHED, первый раунд при старте) — Tasks 3, 5; раздел 13 (rounds/leaderboard/results/vote, X-Total-Count, коды ошибок) — Task 7; раздел 11 (таблицы, связи, индексы) — Task 6; раздел 14 (пирамида, конкурентные) — Tasks 2–8. Гостевые сессии и GUEST-субъект — итерация 8 (раздел 9: гость голосует только в глобальных окнах — итерация 7), в плане явно отложено.
- **Placeholder-скан:** два места помечены «свериться перед реализацией» (`StartTournamentServiceTest` tx-шаблон, `JpaVotingWindowRepositoryContractIT` аннотации/FK-tournament) — это указания свериться с существующими образцами, а не «TBD»; код для обоих путей приведён.
- **Консистентность типов:** `VotingWindow.close → CloseOutcome(eliminatedEntryIds, survivedEntryIds, winnerEntryId)` одинаково используется в Task 3 (домен) и Task 5 (сервис); `findByIdForUpdate` объявлен в порте (Task 2), реализован in-memory (Task 4) и JPA (Task 6), используется `VotingService` (Task 4) и `CloseVotingWindowService` (Task 5); `PlantLifecycleGateway` (Task 1) потребляется `CloseVotingWindowService` (Task 5) и реализуется `InProcessPlantLifecycle` (Task 7).
