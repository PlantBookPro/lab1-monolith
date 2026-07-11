# Итерация 8 (лента и гостевые сессии) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Раздел 9 требований: контекст `feed` (проекция карточек по событиям, ADR-002), агрегат `GuestSession` (хэш токена, TTL), субъект `GUEST` в голосовании (только глобальные окна), `FeedCursor` с HMAC и keyset-пагинацией `{items,nextCursor,hasNext}` без total, псевдослучайный sort key в SQL, лимиты 429 с `Retry-After`, REST `/feed` и `/guest-sessions`, docs (ADR-013, глоссарий, aggregates, context map, README).

**Architecture:** Модульный монолит, новый контекст `feed` (read-модель, ADR-002) + расширение `tournaments` (GuestSession — таблица в схеме tournaments, раздел 11; субъект GUEST; read-контракты `FeedDirectory`/`GuestSessionDirectory` в `tournaments.api`). feed — Downstream: только `api` владельцев через ACL-адаптеры (ArchUnit-матрица уже разрешает feed → tournaments, plants, media, identity). Проекция обновляется новыми опубликованными событиями `VotingWindowOpened`/`VotingWindowClosed` (синхронный `@EventListener` в той же tx, как `PlantModerationDecidedHandler`). Гость разрешается в use case по заголовку `X-Guest-Token` (гостевые сессии — понятие tournaments, identity их не знает). Дизайн: `docs/specs/2026-09-28-iteration-8-feed-design.md`.

**Tech Stack:** Java 21, Spring Boot 4.0.8 (MVC, Data JPA), PostgreSQL 17.5 + Flyway по контекстам (ADR-003; новые: `tournaments/V5__guest_sessions.sql`, `feed/V2__feed_cards.sql`), HMAC-SHA256 (`javax.crypto`, без новых зависимостей), `hashtextextended` PostgreSQL для sort key, Testcontainers 2.0.5, ArchUnit 1.5.0, JaCoCo 0.8.15, Awaitility (test).

## Global Constraints

- Ветка `feat/iteration-8-feed` (от `main`); Conventional Commits; тесты коммитируются вместе с реализацией или раньше; красные тесты в `main` не попадают.
- TDD (раздел 14): сначала красные приёмочные `GuestSessionsApiIT`/`FeedApiIT`/`FeedCursorApiIT` (Task 1), затем внутренний цикл red→green→refactor.
- `./mvnw verify` — единственная команда проверки; совокупный LINE coverage ≥ 70% (gate); каждый коммит задачи — зелёный verify (кроме Task 1 с красными приёмочными — установленный паттерн внешнего цикла, как в итерациях 5–7).
- Все ID — UUID; время — `Instant`/UTC из внедрённого `Clock`; домен получает `Instant now` аргументом, не `Instant.now()` (`SecureRandom` в `FeedOrdering`/`GuestTokens` — исключение по природе операции, JDK-only).
- Enum — VARCHAR + CHECK в миграциях, маппинг явный (`.name()`); Hibernate `ddl-auto=validate`; Flyway — единственный источник структуры. `scope` в проекции feed — строка-снимок события, без доменных правил.
- Границы контекстов — только через `api` и только из адаптеров (ArchUnit; направление feed → {tournaments, plants, media, identity} уже разрешено `ContextBoundaryTest`). `feed.domain`/`application` и `tournaments.domain`/`application` не импортируют чужие контексты; `shared.security.CurrentActor` — разрешённая техническая зависимость application.
- **Урок итерации 2:** абстрактные базовые классы контрактных тестов репозиториев несут `@Transactional` на себе.
- **Урок итераций 5–7:** @Service-бины application появляются только после JPA-реализаций новых портов — порядок задач: домен → persistence → application → in-адаптеры.
- Новые понятия — сначала в `docs/domain/glossary.md` (Гостевая сессия/GuestSession, Курсор ленты/FeedCursor, Карточка ленты/FeedCard, Seed ленты), изменения правил — сначала docs (aggregates, ADR-013), затем код (Task 11 замыкает итерацию).
- REST (раздел 13): `POST /guest-sessions` — публично, 201, токен в теле один раз; лимит — 429 `RATE_LIMITED` + `Retry-After`; `GET /feed` — U либо guest token, `{items,nextCursor,hasNext}` без total и без `X-Total-Count`; limit 1–50 по умолчанию 20, вне диапазона → 400; невалидный cursor → 400 `FEED_CURSOR_INVALID`, истёкший → 410 `FEED_CURSOR_EXPIRED`; PRIVATE-окно гостя — 404 `TOURNAMENT_NOT_FOUND` (скрыто); гость без/с плохим токеном — 401; уникальные operationId с префиксами `guest-`/`feed-`.
- Среда: macOS/zsh; рабочая директория `lab1-monolith/`; scratch-файлы диагностики не коммитировать.

## Карта файлов итерации

```text
src/main/java/com/plantarena/feed/                                НОВЫЙ КОД КОНТЕКСТА
├── domain/
│   ├── FeedCard.java                                             НОВОЕ (Task 6)
│   ├── FeedCardQuery.java                                        НОВОЕ (Task 6)
│   ├── FeedCardRepository.java                                   НОВОЕ (Task 6)
│   ├── FeedCursor.java                                           НОВОЕ (Task 6)
│   └── FeedOrdering.java                                         НОВОЕ (Task 6)
├── application/
│   ├── FeedSettings.java                                         НОВОЕ (Task 8)
│   ├── FeedCursorCodec.java                                      НОВОЕ (Task 8)
│   ├── FeedCursorInvalidException.java                           НОВОЕ (Task 8)
│   ├── FeedCursorExpiredException.java                           НОВОЕ (Task 8)
│   ├── FeedService.java                                          НОВОЕ (Task 8)
│   ├── FeedProjectionService.java                                НОВОЕ (Task 8)
│   ├── port/in/GetFeedUseCase.java                               НОВОЕ (Task 8)
│   ├── port/in/ProjectWindowUseCase.java                         НОВОЕ (Task 8)
│   └── port/out/{VotingDirectory, GuestSessions, PlantCatalog,
│   │            OwnerDirectory}.java                             НОВОЕ (Task 1)
└── adapter/
    ├── in/web/{FeedController, FeedItemResponse, FeedPageResponse,
    │           OwnerResponse, FeedExceptionHandler}.java         НОВОЕ (Task 8 handler, Task 10 controller)
    ├── in/events/{VotingWindowOpenedHandler,
    │              VotingWindowClosedHandler}.java                НОВОЕ (Task 8)
    └── out/
        ├── persistence/{FeedCardJpaEntity, FeedCardJpaRepository,
        │                 JpaFeedCardRepository}.java              НОВОЕ (Task 6)
        ├── tournaments/{InProcessVotingDirectory,
        │                 InProcessGuestSessions}.java             НОВОЕ (Task 8)
        ├── plants/InProcessPlantCatalog.java                     НОВОЕ (Task 8)
        └── identity/InProcessOwnerDirectory.java                 НОВОЕ (Task 8)

src/main/java/com/plantarena/tournaments/
├── api/
│   ├── FeedDirectory.java                                        НОВОЕ (Task 7)
│   └── GuestSessionDirectory.java                                НОВОЕ (Task 7)
├── api/event/
│   ├── VotingWindowOpenedEvent.java                              НОВОЕ (Task 5)
│   └── VotingWindowClosedEvent.java                              НОВОЕ (Task 5)
├── domain/
│   ├── GuestSession.java                                         НОВОЕ (Task 2)
│   ├── GuestSessionRepository.java                               НОВОЕ (Task 2)
│   ├── VotingSubject.java                                        ИЗМЕНЕНО: guest(sessionId) (Task 2)
│   ├── VotingWindowRepository.java                               ИЗМЕНЕНО: findVotedEntryIdsInOpenWindows (Task 7)
│   └── TournamentEntryRepository.java                            ИЗМЕНЕНО: findTournamentIdsByUserId (Task 7)
├── application/
│   ├── GuestTokens.java                                          НОВОЕ (Task 4)
│   ├── GuestSessionsSettings.java                                НОВОЕ (Task 4)
│   ├── FixedWindowRateLimiter.java                               НОВОЕ (Task 4)
│   ├── RateLimitExceededException.java                           НОВОЕ (Task 4)
│   ├── GuestSessionService.java                                  НОВОЕ (Task 4)
│   ├── FeedDirectoryFacade.java                                  НОВОЕ (Task 7)
│   ├── GuestSessionDirectoryFacade.java                          НОВОЕ (Task 7)
│   ├── VotingService.java                                        ИЗМЕНЕНО: гость + лимит (Task 9)
│   ├── port/in/CreateGuestSessionUseCase.java                    НОВОЕ (Task 4)
│   ├── port/in/VotingUseCase.java                                ИЗМЕНЕНО: guestToken (Task 9)
│   └── port/out/AbuseSignals.java                                НОВОЕ (Task 4)
└── adapter/
    ├── in/web/
    │   ├── GuestSessionController.java                           НОВОЕ (Task 4)
    │   ├── GuestSessionResponse.java                             НОВОЕ (Task 4)
    │   ├── TournamentsExceptionHandler.java                      ИЗМЕНЕНО: 429 + Retry-After (Task 4)
    │   └── VotingController.java                                 ИЗМЕНЕНО: X-Guest-Token (Task 9)
    ├── out/abuse/LoggingAbuseSignals.java                        НОВОЕ (Task 4)
    └── out/persistence/
        ├── GuestSessionJpaEntity.java                            НОВОЕ (Task 3)
        ├── GuestSessionJpaRepository.java                        НОВОЕ (Task 3)
        ├── JpaGuestSessionRepository.java                        НОВОЕ (Task 3)
        ├── VoteJpaRepository.java                                НОВОЕ (Task 7)
        ├── JpaVotingWindowRepository.java                        ИЗМЕНЕНО (Task 7)
        ├── TournamentEntryJpaRepository.java                     ИЗМЕНЕНО (Task 7)
        └── JpaTournamentEntryRepository.java                     ИЗМЕНЕНО (Task 7)

src/main/java/com/plantarena/config/
├── TournamentsWiringConfig.java                                  ИЗМЕНЕНО (Task 4)
├── FeedWiringConfig.java                                         НОВОЕ (Task 8)
└── OpenApiConfig.java                                            ИЗМЕНЕНО: X-Guest-Token (Task 9)

src/main/resources/db/migration/
├── tournaments/V5__guest_sessions.sql                            НОВОЕ (Task 3)
└── feed/V2__feed_cards.sql                                       НОВОЕ (Task 6)

src/main/resources/application.yml                                ИЗМЕНЕНО: plantarena.guests (Task 4), plantarena.feed (Task 8)

src/test/java/com/plantarena/
├── feed/
│   ├── FeedApiIT.java                                            НОВОЕ (Task 1 красный, Task 10 зелёный)
│   ├── FeedCursorApiIT.java                                      НОВОЕ (Task 1 красный, Task 10 зелёный)
│   ├── FeedCardRepositoryContractTest.java                       НОВОЕ (Task 6)
│   ├── domain/FeedCursorTest.java                                НОВОЕ (Task 6)
│   ├── application/{FeedCursorCodecTest, FeedServiceTest,
│   │               FeedProjectionServiceTest}.java               НОВОЕ (Task 8)
│   ├── application/support/{InMemoryFeedCardRepository,
│   │                        InMemoryFeedCardRepositoryContractTest,
│   │                        FakeVotingDirectory, FakeGuestSessions}.java  НОВОЕ (Task 6/8)
│   └── adapter/out/persistence/JpaFeedCardRepositoryContractIT.java  НОВОЕ (Task 6)
└── tournaments/
    ├── GuestSessionsApiIT.java                                   НОВОЕ (Task 1 красный, Task 4 зелёный)
    ├── domain/{GuestSessionTest, VotingSubjectTest}.java         НОВОЕ (Task 2)
    ├── GuestSessionRepositoryContractTest.java                   НОВОЕ (Task 3)
    ├── application/support/{InMemoryGuestSessionRepository,
    │                        InMemoryGuestSessionRepositoryContractTest}.java  НОВОЕ (Task 3)
    ├── application/{GuestSessionServiceTest, FeedDirectoryFacadeTest,
    │               GuestSessionDirectoryFacadeTest}.java         НОВОЕ (Task 4/7)
    ├── application/VotingServiceTest.java                        ИЗМЕНЕНО (Task 9)
    ├── application/{StartTournamentServiceTest, CloseVotingWindowServiceTest,
    │                AdvanceGlobalCompetitionServiceTest}.java    ИЗМЕНЕНО: события (Task 5)
    ├── VotingWindowRepositoryContractTest.java                   ИЗМЕНЕНО (Task 7)
    ├── TournamentEntryRepositoryContractTest.java                ИЗМЕНЕНО (Task 7)
    └── adapter/out/persistence/{JpaVotingWindowRepositoryContractIT,
                                 JpaTournamentEntryRepositoryContractIT}.java  ИЗМЕНЕНО (Task 7)

docs/domain/adr/ADR-013-guest-sessions.md                         НОВОЕ (Task 11)
docs/domain/adr/ADR-002-feed-own-projection.md                    ИЗМЕНЕНО: реализовано (Task 11)
docs/domain/{glossary,aggregates,context-map}.md, README.md       ИЗМЕНЕНО (Task 11)
.env.example, docker-compose.yml                                  ИЗМЕНЕНО (Task 11)
docs/specs/2026-09-28-iteration-8-feed-design.md                  СОЗДАН до плана
```

---

### Task 1: Порты out feed, красные приёмочные IT

**Files:**
- Create: `src/main/java/com/plantarena/feed/application/port/out/VotingDirectory.java`
- Create: `src/main/java/com/plantarena/feed/application/port/out/GuestSessions.java`
- Create: `src/main/java/com/plantarena/feed/application/port/out/PlantCatalog.java`
- Create: `src/main/java/com/plantarena/feed/application/port/out/OwnerDirectory.java`
- Test: `src/test/java/com/plantarena/tournaments/GuestSessionsApiIT.java`
- Test: `src/test/java/com/plantarena/feed/FeedApiIT.java`
- Test: `src/test/java/com/plantarena/feed/FeedCursorApiIT.java`

**Interfaces:**
- Consumes: REST итераций 1–7, `AbstractIntegrationTest`, эталонный `green-8x8.png`, `DeterministicPlantClassifier`, `PUT /api/v1/me/location`, `POST /api/v1/global/entries`, `POST /api/v1/internal/demo/jobs/run-due`.
- Produces (для Tasks 4–10): порты feed — `VotingDirectory { Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey); Set<UUID> findParticipatedTournamentIds(UUID userId); }`; `GuestSessions { Optional<UUID> activeSessionId(String rawToken); }`; `PlantCatalog { Optional<PlantView> findPlant(UUID plantId); record PlantView(UUID plantId, UUID assetId, String title, String lifeStatus) }`; `OwnerDirectory { Optional<OwnerView> findOwner(UUID userId); record OwnerView(UUID userId, String displayName) }`. Красные `GuestSessionsApiIT` (зелёный в Task 4), `FeedApiIT`/`FeedCursorApiIT` (зелёные в Task 10).

- [ ] **Step 1: Ветка**

```bash
git checkout main && git pull --ff-only 2>/dev/null; git checkout -b feat/iteration-8-feed
```

- [ ] **Step 2: Порты out feed (consumer-driven, раздел 4.3)**

`src/main/java/com/plantarena/feed/application/port/out/VotingDirectory.java`:

```java
package com.plantarena.feed.application.port.out;

import java.util.Set;
import java.util.UUID;

/**
 * Выходной порт feed: данные голосования из tournaments (раздел 9).
 * Адаптер — ACL над tournaments.api.FeedDirectory; feed не читает чужие
 * таблицы (ADR-002).
 */
public interface VotingDirectory {

    /** Entry, уже оценённые субъектом в открытых окнах (карточки не предлагаются). */
    Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey);

    /** Турниры, где пользователь был допущен к старту (включая выбывших — допущение 9). */
    Set<UUID> findParticipatedTournamentIds(UUID userId);
}
```

`src/main/java/com/plantarena/feed/application/port/out/GuestSessions.java`:

```java
package com.plantarena.feed.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Выходной порт feed: гостевые сессии tournaments (раздел 9). Адаптер —
 * ACL над tournaments.api.GuestSessionDirectory.
 */
public interface GuestSessions {

    /** Активная сессия по сырому токену: проверяет хэш и срок действия. */
    Optional<UUID> activeSessionId(String rawToken);
}
```

`src/main/java/com/plantarena/feed/application/port/out/PlantCatalog.java`:

```java
package com.plantarena.feed.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Выходной порт feed: публичные данные растения для карточки (раздел 9).
 * Адаптер — ACL над plants.api.PlantDirectory.
 */
public interface PlantCatalog {

    Optional<PlantView> findPlant(UUID plantId);

    /** Публичный минимум: asset для URL, title, жизнь (неживое не показывается). */
    record PlantView(UUID plantId, UUID assetId, String title, String lifeStatus) {
    }
}
```

`src/main/java/com/plantarena/feed/application/port/out/OwnerDirectory.java`:

```java
package com.plantarena.feed.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Выходной порт feed: минимальные публичные сведения о владельце (раздел 9:
 * не приватный профиль, email и координаты). Адаптер — ACL над
 * identity.api.UserDirectory.
 */
public interface OwnerDirectory {

    Optional<OwnerView> findOwner(UUID userId);

    record OwnerView(UUID userId, String displayName) {
    }
}
```

- [ ] **Step 3: Красный приёмочный `GuestSessionsApiIT`**

`src/test/java/com/plantarena/tournaments/GuestSessionsApiIT.java`:

```java
package com.plantarena.tournaments;

import com.jayway.jsonpath.JsonPath;
import com.plantarena.support.AbstractIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Внешний цикл TDD итерации 8: гостевые сессии (раздел 9) через HTTP.
 * Лимит выдачи занижен до 3/мин; фиксированное окно 1 минута — тесты
 * класса укладываются в одно окно, цикл добивает до 429 независимо от
 * порядка методов. Красный до Task 4 (ручки /guest-sessions нет).
 */
@TestPropertySource(properties = {
    "plantarena.guests.session-creation-limit-per-minute=3"
})
@DisplayName("Сценарии раздела 9: гостевые сессии — токен один раз, лимит 429")
class GuestSessionsApiIT extends AbstractIntegrationTest {

    private static final String GUEST_TOKEN_HEADER = "X-Guest-Token";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("создание: публично, 201 с токеном и expiresAt; токены непредсказуемы (разные)")
    void создание_сессии() throws Exception {
        String body1 = createSession();
        String body2 = createSession();

        String token1 = JsonPath.read(body1, "$.token");
        String token2 = JsonPath.read(body2, "$.token");
        assertThat(token1).isNotBlank();
        assertThat(token2).isNotBlank().isNotEqualTo(token1);
        assertThat(Instant.parse(JsonPath.read(body1, "$.expiresAt")))
            .isAfter(Instant.now());
    }

    @Test
    @DisplayName("лимит выдачи с одного IP: 429 RATE_LIMITED + Retry-After (раздел 9)")
    void лимит_выдачи() throws Exception {
        boolean limited = false;
        for (int i = 0; i < 6 && !limited; i++) {
            MvcResult result = mockMvc.perform(post("/api/v1/guest-sessions")).andReturn();
            int status = result.getResponse().getStatus();
            if (status == 201) {
                continue;
            }
            if (status == 429) {
                limited = true;
                assertThat(result.getResponse().getHeader("Retry-After")).isNotBlank();
                assertThat(JsonPath.read(result.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo("RATE_LIMITED");
            }
        }
        assertThat(limited)
            .as("превышение лимита выдачи должно давать 429 с Retry-After")
            .isTrue();
    }
}
```

- [ ] **Step 4: Красный приёмочный `FeedApiIT`**

`src/test/java/com/plantarena/feed/FeedApiIT.java`:

```java
package com.plantarena.feed;

import com.jayway.jsonpath.JsonPath;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.support.DeterministicPlantClassifier;
import com.plantarena.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Внешний цикл TDD итерации 8: лента (раздел 9) через HTTP на Testcontainers.
 * Классификатор детерминированный (зелёный PNG → APPROVED). Тайминги
 * глобального режима длинные (PT2M) — окна не закрываются посреди тестов;
 * закрытие окна проверяется отдельным сценарием на закрытом турнире.
 * Каждый тест строит свой мир (свежие пользователи/почта).
 *
 * <p>Важно (допущение 4): участник закрытого турнира не имеет активной
 * глобальной заявки — одно изображение (green-8x8.png) не участвует в двух
 * турнирах одновременно, поэтому глобальные и закрытые участники не
 * пересекаются.
 */
@TestPropertySource(properties = {
    "plantarena.global.epoch-duration=PT2M",
    "plantarena.global.final-window-duration=PT2M",
    "plantarena.geo.geohash-precision=4"
})
@DisplayName("Сценарии раздела 9: лента — смешение, права, keyset, гости")
class FeedApiIT extends AbstractIntegrationTest {

    private static final String DEMO_HEADER = "X-Demo-User-Id";
    private static final String GUEST_TOKEN_HEADER = "X-Guest-Token";
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
    @DisplayName("лента пользователя смешивает глобальные и закрытый турнир; гость и посторонний — только глобальные; без total")
    void лента_смешивает_и_фильтрует_по_правам() throws Exception {
        // глобальный турнир: u2 (Москва) и u3 (СПб) — два кластера, два окна
        UUID u2 = newUser("mix-u2@example.com", "Mix2");
        UUID u3 = newUser("mix-u3@example.com", "Mix3");
        setLocation(u2, LAT_MOSCOW, LON_MOSCOW);
        setLocation(u3, LAT_SPB, LON_SPB);
        UUID p2 = approvedPlantOf(u2, "Фикус u2");
        UUID p3 = approvedPlantOf(u3, "Фикус u3");
        UUID e2 = submitGlobalEntry(u2, p2);
        UUID e3 = submitGlobalEntry(u3, p3);
        awaitGlobalCards(2);

        // закрытый турнир: u1 + u4 (без глобальных заявок — допущение 4)
        UUID u1 = newUser("mix-u1@example.com", "Mix1");
        UUID u4 = newUser("mix-u4@example.com", "Mix4");
        UUID p1 = approvedPlantOf(u1, "Фикус u1");
        UUID p4 = approvedPlantOf(u4, "Фикус u4");
        UUID tournamentId = createPrivateTournament(600);
        invite(tournamentId, u1);
        invite(tournamentId, u4);
        acceptInvitation(u1, p1);
        acceptInvitation(u4, p4);
        awaitRunning(tournamentId);

        // u1 — участник закрытого: глобальные карточки u2/u3 + закрытая u4; своих нет
        String feed = feedOf(u1);
        assertThat(entryIds(feed)).hasSize(3);
        assertThat(entryIds(feed)).doesNotContain(entryIdOfTournament(tournamentId, u1).toString());
        assertThat(entryIds(feed)).contains(e2.toString(), e3.toString(),
            entryIdOfTournament(tournamentId, u4).toString());

        // форма карточки (раздел 9): windowId, scope, ids, title, image URL, публичный владелец, closesAt
        List<Map<String, Object>> qualification = JsonPath.read(feed,
            "$.items[?(@.scope == 'QUALIFICATION')]");
        assertThat(qualification).hasSize(2);
        Map<String, Object> card = qualification.get(0);
        assertThat(card).containsKeys("windowId", "tournamentId", "entryId", "plantId",
            "title", "imageUrl", "owner", "closesAt");
        assertThat((String) card.get("imageUrl")).startsWith("/api/v1/files/");
        assertThat((Map<String, Object>) card.get("owner")).containsKeys("userId", "displayName");

        // посторонний (не участник закрытого) — только глобальные
        UUID stranger = newUser("mix-stranger@example.com", "Stranger");
        String strangerFeed = feedOf(stranger);
        assertThat(entryIds(strangerFeed)).containsExactlyInAnyOrder(e2.toString(), e3.toString());

        // гость — только глобальные, без total (раздел 13)
        String token = createGuestSession();
        String guestFeed = mockMvc.perform(get("/api/v1/feed")
                .header(GUEST_TOKEN_HEADER, token))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("X-Total-Count"))
            .andExpect(jsonPath("$.hasNext").value(false))
            .andExpect(jsonPath("$.nextCursor").value(nullValue()))
            .andReturn().getResponse().getContentAsString();
        assertThat(entryIds(guestFeed)).containsExactlyInAnyOrder(e2.toString(), e3.toString());
    }

    @Test
    @DisplayName("голосование гостя: глобальные окна LIKE/смена/удаление; закрытое окно — 404; без токена — 401; USER при обоих заголовках")
    void голосование_гостя() throws Exception {
        UUID u1 = newUser("gvote-u1@example.com", "GVote1");
        UUID u2 = newUser("gvote-u2@example.com", "GVote2");
        UUID stranger = newUser("gvote-stranger@example.com", "Stranger");
        setLocation(u1, LAT_MOSCOW, LON_MOSCOW);
        setLocation(u2, LAT_MOSCOW, LON_MOSCOW);
        setLocation(stranger, LAT_MOSCOW, LON_MOSCOW);
        UUID p1 = approvedPlantOf(u1, "Фикус u1");
        UUID p2 = approvedPlantOf(u2, "Фикус u2");
        UUID e1 = submitGlobalEntry(u1, p1);
        UUID e2 = submitGlobalEntry(u2, p2);
        awaitGlobalCards(2);
        UUID windowId = UUID.fromString(JsonPath.read(feedOf(u1), "$.items[0].windowId"));

        String token = createGuestSession();
        String votePath = "/api/v1/windows/" + windowId + "/entries/" + e2 + "/vote";

        // LIKE → 1; смена на DISLIKE → −1 (дельта −2); удаление → 0 (раздел 9)
        mockMvc.perform(put(votePath).header(GUEST_TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.score").value(1));
        mockMvc.perform(get(votePath + "/my-vote").header(GUEST_TOKEN_HEADER, token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("LIKE"));
        mockMvc.perform(put(votePath).header(GUEST_TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"DISLIKE\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.score").value(-1));
        mockMvc.perform(delete(votePath).header(GUEST_TOKEN_HEADER, token))
            .andExpect(status().isNoContent());
        mockMvc.perform(get(votePath + "/my-vote").header(GUEST_TOKEN_HEADER, token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.value").value(nullValue()));

        // без токена и с неизвестным токеном — 401
        mockMvc.perform(put(votePath)
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(put(votePath).header(GUEST_TOKEN_HEADER, "not-a-real-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isUnauthorized());

        // идентифицированный пользователь при обоих заголовках — субъект USER (раздел 9)
        mockMvc.perform(put(votePath)
                .header(DEMO_HEADER, stranger.toString())
                .header(GUEST_TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.score").value(1));
        mockMvc.perform(get(votePath + "/my-vote").header(DEMO_HEADER, stranger.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("LIKE"));

        // закрытое окно для гостя — 404 (турнир скрыт, раздел 13)
        UUID u3 = newUser("gvote-u3@example.com", "GVote3");
        UUID u4 = newUser("gvote-u4@example.com", "GVote4");
        UUID p3 = approvedPlantOf(u3, "Фикус u3");
        UUID p4 = approvedPlantOf(u4, "Фикус u4");
        UUID privateTournament = createPrivateTournament(600);
        invite(privateTournament, u3);
        invite(privateTournament, u4);
        acceptInvitation(u3, p3);
        acceptInvitation(u4, p4);
        awaitRunning(privateTournament);
        UUID e4 = entryIdOfTournament(privateTournament, u4);
        String privateFeed = feedOf(u3);
        UUID privateWindowId = windowIdOf(privateFeed, u4);
        mockMvc.perform(put("/api/v1/windows/" + privateWindowId + "/entries/" + e4 + "/vote")
                .header(GUEST_TOKEN_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("TOURNAMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("keyset-пагинация: limit+1 → hasNext/nextCursor, страницы не пересекаются; limit вне 1–50 — 400")
    void пагинация_keyset() throws Exception {
        UUID u1 = newUser("page-u1@example.com", "Page1");
        UUID u2 = newUser("page-u2@example.com", "Page2");
        UUID u3 = newUser("page-u3@example.com", "Page3");
        setLocation(u1, LAT_MOSCOW, LON_MOSCOW);
        setLocation(u2, LAT_MOSCOW, LON_MOSCOW);
        setLocation(u3, LAT_SPB, LON_SPB);
        submitGlobalEntry(u1, approvedPlantOf(u1, "Фикус u1"));
        submitGlobalEntry(u2, approvedPlantOf(u2, "Фикус u2"));
        submitGlobalEntry(u3, approvedPlantOf(u3, "Фикус u3"));
        awaitGlobalCards(3);

        // u4 — участник закрытого (u4+u5): 4-я карточка
        UUID u4 = newUser("page-u4@example.com", "Page4");
        UUID u5 = newUser("page-u5@example.com", "Page5");
        UUID p4 = approvedPlantOf(u4, "Фикус u4");
        UUID p5 = approvedPlantOf(u5, "Фикус u5");
        UUID tournamentId = createPrivateTournament(600);
        invite(tournamentId, u4);
        invite(tournamentId, u5);
        acceptInvitation(u4, p4);
        acceptInvitation(u5, p5);
        awaitRunning(tournamentId);

        String page1 = mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, u4.toString()).queryParam("limit", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty())
            .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");

        String page2 = mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, u4.toString())
                .queryParam("limit", "2").queryParam("cursor", cursor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.hasNext").value(false))
            .andExpect(jsonPath("$.nextCursor").value(nullValue()))
            .andReturn().getResponse().getContentAsString();

        Set<String> union = Set.copyOf(entryIds(page1));
        Set<String> union2 = Set.copyOf(entryIds(page2));
        assertThat(union).hasSize(2);
        assertThat(union2).hasSize(2);
        assertThat(union.stream().filter(union2::contains)).isEmpty(); // без дублей и пропусков
        assertThat(union.size() + union2.size()).isEqualTo(4);

        // limit вне 1–50 — 400 (раздел 13)
        mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, u4.toString()).queryParam("limit", "0"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, u4.toString()).queryParam("limit", "51"))
            .andExpect(status().isBadRequest());

        // невалидный курсор — 400
        mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, u4.toString()).queryParam("cursor", "garbage"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("FEED_CURSOR_INVALID"));

        // курсор u4 не даёт прав на ленту u5 (раздел 9)
        mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, u5.toString()).queryParam("cursor", cursor))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("FEED_CURSOR_INVALID"));
    }

    @Test
    @DisplayName("оцененные субъектом карточки не предлагаются; новые после snapshotCutoff — после обновления ленты")
    void оцененные_и_новые_после_cutoff() throws Exception {
        UUID u1 = newUser("cut-u1@example.com", "Cut1");
        UUID u2 = newUser("cut-u2@example.com", "Cut2");
        UUID u3 = newUser("cut-u3@example.com", "Cut3");
        setLocation(u1, LAT_MOSCOW, LON_MOSCOW);
        setLocation(u2, LAT_MOSCOW, LON_MOSCOW);
        setLocation(u3, LAT_SPB, LON_SPB);
        UUID e1 = submitGlobalEntry(u1, approvedPlantOf(u1, "Фикус u1"));
        UUID e2 = submitGlobalEntry(u2, approvedPlantOf(u2, "Фикус u2"));
        UUID e3 = submitGlobalEntry(u3, approvedPlantOf(u3, "Фикус u3"));
        awaitGlobalCards(3);

        UUID u4 = newUser("cut-u4@example.com", "Cut4");
        // u4 голосует за e2 — карточка исчезает из его ленты (раздел 9)
        UUID windowId = UUID.fromString(JsonPath.read(feedOf(u4), "$.items[0].windowId"));
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + e2 + "/vote")
                .header(DEMO_HEADER, u4.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isOk());
        Set<String> before = Set.copyOf(entryIds(feedOf(u4))); // {e1, e3}
        assertThat(before).containsExactlyInAnyOrder(e1.toString(), e3.toString());

        // страница 1 фиксирует snapshotCutoff
        String page1 = mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, u4.toString()).queryParam("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");

        // новый закрытый турнир ПОСЛЕ cutoff: u4 + u5
        UUID u5 = newUser("cut-u5@example.com", "Cut5");
        UUID p4 = approvedPlantOf(u4, "Фикус u4");
        UUID p5 = approvedPlantOf(u5, "Фикус u5");
        UUID tournamentId = createPrivateTournament(600);
        invite(tournamentId, u4);
        invite(tournamentId, u5);
        acceptInvitation(u4, p4);
        acceptInvitation(u5, p5);
        awaitRunning(tournamentId);

        // продолжение курсора: только карточки до cutoff, без новых
        String page2 = mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, u4.toString()).queryParam("cursor", cursor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.hasNext").value(false))
            .andReturn().getResponse().getContentAsString();
        Set<String> continuation = Set.copyOf(entryIds(page1));
        continuation.addAll(entryIds(page2));
        assertThat(continuation).isEqualTo(before); // новые не появились, старые не потеряны

        // свежая лента содержит новую карточку u5 (после обновления ленты)
        String fresh = feedOf(u4);
        assertThat(entryIds(fresh)).hasSize(3);
        assertThat(entryIds(fresh)).contains(entryIdOfTournament(tournamentId, u5).toString());
    }

    @Test
    @DisplayName("закрытие окна убирает карточки: раунд закрыт — карточек турнира нет, проигравший погиб")
    void закрытие_окна_убирает_карточки() throws Exception {
        UUID u6 = newUser("close-u6@example.com", "Close6");
        UUID u7 = newUser("close-u7@example.com", "Close7");
        UUID p6 = approvedPlantOf(u6, "Фикус u6");
        UUID p7 = approvedPlantOf(u7, "Фикус u7");
        UUID tournamentId = createPrivateTournament(3); // раунд 3 секунды
        invite(tournamentId, u6);
        invite(tournamentId, u7);
        acceptInvitation(u6, p6);
        acceptInvitation(u7, p7);
        awaitRunning(tournamentId);

        // u7 голосует за u6 — u6 выживает при закрытии (tie-break иначе непредсказуем)
        UUID e6 = entryIdOfTournament(tournamentId, u6);
        String feed6 = feedOf(u6);
        UUID e7 = UUID.fromString(entryIdOf(feed6, u7));
        UUID windowId = windowIdOf(feed6, u7);
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + e6 + "/vote")
                .header(DEMO_HEADER, u7.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isOk());

        // до закрытия карточка u7 в ленте u6
        assertThat(entryIds(feedOf(u6))).contains(e7.toString());

        // закрытие раунда: u7 выбывает, растение гибнет (PERMANENT), карточки исчезают
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                runDue();
                mockMvc.perform(get("/api/v1/plants/" + p7).header(DEMO_HEADER, u7.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lifeStatus").value("DEAD"));
            });
        assertThat(JsonPath.read(feedOf(u6),
            "$.items[?(@.tournamentId == '" + tournamentId + "')]")).isEmpty();
    }

    // ---------- helpers ----------

    private String feedOf(UUID user) throws Exception {
        return mockMvc.perform(get("/api/v1/feed").header(DEMO_HEADER, user.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private List<String> entryIds(String feedJson) {
        return JsonPath.read(feedJson, "$.items[*].entryId");
    }

    private UUID windowIdOf(String feedJson, UUID ownerId) {
        List<Map<String, Object>> items = JsonPath.read(feedJson,
            "$.items[?(@.owner.userId == '" + ownerId + "')]");
        assertThat(items).hasSize(1);
        return UUID.fromString((String) items.get(0).get("windowId"));
    }

    private UUID entryIdOfTournament(UUID tournamentId, UUID user) throws Exception {
        String body = mockMvc.perform(get("/api/v1/tournaments/" + tournamentId + "/entries")
                .header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> mine = JsonPath.read(body,
            "$.items[?(@.userId == '" + user + "')]");
        assertThat(mine).hasSize(1);
        return UUID.fromString((String) mine.get(0).get("id"));
    }

    private String createGuestSession() throws Exception {
        return mockMvc.perform(post("/api/v1/guest-sessions"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    }

    private void awaitGlobalCards(int expected) throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                runDue();
                String feed = mockMvc.perform(get("/api/v1/feed")
                        .header(DEMO_HEADER, adminId().toString()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
                assertThat(entryIds(feed)).hasSize(expected);
            });
    }

    private UUID createPrivateTournament(int roundDurationSeconds) throws Exception {
        String body = mockMvc.perform(post("/api/v1/tournaments")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Закрытый %s","registrationDeadline":"%s","roundDurationSeconds":%d,
                     "eliminationFraction":0.5,"minParticipants":2}
                    """.formatted(UUID.randomUUID(), Instant.now().plusSeconds(2),
                        roundDurationSeconds)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void invite(UUID tournamentId, UUID userId) throws Exception {
        mockMvc.perform(post("/api/v1/tournaments/" + tournamentId + "/invitations")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"%s\"}".formatted(userId)))
            .andExpect(status().isCreated());
    }

    private void acceptInvitation(UUID user, UUID plantId) throws Exception {
        String mine = mockMvc.perform(get("/api/v1/me/invitations")
                .header(DEMO_HEADER, user.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        UUID invitationId = UUID.fromString(JsonPath.read(mine, "$.items[0].id"));
        mockMvc.perform(post("/api/v1/invitations/" + invitationId + "/accept")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY"));
    }

    private void awaitRunning(UUID tournamentId) throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                runDue();
                mockMvc.perform(get("/api/v1/tournaments/" + tournamentId)
                        .header(DEMO_HEADER, adminId().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RUNNING"));
            });
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
        UUID plantId = createPlantOf(user, title);
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> mockMvc.perform(get("/api/v1/plants/" + plantId + "/moderation")
                    .header(DEMO_HEADER, user.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("APPROVED")));
        return plantId;
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

    private UUID newUser(String email, String displayName) throws Exception {
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

- [ ] **Step 5: Красный приёмочный `FeedCursorApiIT`**

`src/test/java/com/plantarena/feed/FeedCursorApiIT.java`:

```java
package com.plantarena.feed;

import com.jayway.jsonpath.JsonPath;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.support.DeterministicPlantClassifier;
import com.plantarena.support.AbstractIntegrationTest;
import java.time.Duration;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Внешний цикл TDD итерации 8: курсор ленты (раздел 9) — невалидный 400,
 * истёкший 410. TTL курсора занижен до 2 секунд: истёкший курсор получается
 * ожиданием, без знания секрета HMAC. Красный до Task 10 (ручки /feed нет).
 */
@TestPropertySource(properties = {
    "plantarena.global.epoch-duration=PT2M",
    "plantarena.global.final-window-duration=PT2M",
    "plantarena.feed.cursor-ttl=PT2S"
})
@DisplayName("Сценарии раздела 9: курсор ленты — 400 невалидный, 410 истёкший")
class FeedCursorApiIT extends AbstractIntegrationTest {

    private static final String DEMO_HEADER = "X-Demo-User-Id";
    private static final double LAT_MOSCOW = 55.7558;
    private static final double LON_MOSCOW = 37.6173;

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
    @DisplayName("невалидный курсор — 400 FEED_CURSOR_INVALID")
    void невалидный_курсор() throws Exception {
        UUID user = newUserWithGlobalCard();
        mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, user.toString()).queryParam("cursor", "garbage"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("FEED_CURSOR_INVALID"));
        mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, user.toString()).queryParam("cursor", "aaaa.bbbb"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("FEED_CURSOR_INVALID"));
    }

    @Test
    @DisplayName("истёкший курсор — 410 FEED_CURSOR_EXPIRED с предложением начать новую ленту")
    void истёкший_курсор() throws Exception {
        UUID user = newUserWithGlobalCard();
        String page1 = mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, user.toString()).queryParam("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasNext").value(true))
            .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");

        Awaitility.await().atLeast(Duration.ofSeconds(3)).until(() -> true); // TTL 2 секунды истёк

        mockMvc.perform(get("/api/v1/feed")
                .header(DEMO_HEADER, user.toString()).queryParam("cursor", cursor))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("FEED_CURSOR_EXPIRED"));

        // новая лента без курсора работает (предложение «начать новую ленту»)
        mockMvc.perform(get("/api/v1/feed").header(DEMO_HEADER, user.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasNext").value(false));
    }

    // ---------- helpers ----------

    /** Пользователь с двумя глобальными карточками в ленте (для limit=1 → hasNext). */
    private UUID newUserWithGlobalCard() throws Exception {
        UUID u1 = newUser("cursor-u1@example.com", "Cursor1");
        UUID u2 = newUser("cursor-u2@example.com", "Cursor2");
        putLocation(u1);
        putLocation(u2);
        submitGlobal(u1);
        submitGlobal(u2);
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                runDue();
                mockMvc.perform(get("/api/v1/feed").header(DEMO_HEADER, adminId().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(2));
            });
        return u1;
    }

    private void putLocation(UUID user) throws Exception {
        mockMvc.perform(put("/api/v1/me/location")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":%s,\"longitude\":%s}".formatted(LAT_MOSCOW, LON_MOSCOW)))
            .andExpect(status().isOk());
    }

    private void submitGlobal(UUID user) throws Exception {
        UUID assetId = uploadGreen(user);
        String plantBody = mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":\"%s\",\"title\":\"Фикус %s\"}"
                    .formatted(assetId, user)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID plantId = UUID.fromString(JsonPath.read(plantBody, "$.id"));
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> mockMvc.perform(
                    get("/api/v1/plants/" + plantId + "/moderation")
                        .header(DEMO_HEADER, user.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("APPROVED")));
        mockMvc.perform(post("/api/v1/global/entries")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plantId)))
            .andExpect(status().isCreated());
    }

    private UUID uploadGreen(UUID user) throws Exception {
        byte[] content;
        try (var in = getClass().getResourceAsStream("/media/reference/green-8x8.png")) {
            content = in.readAllBytes();
        }
        String response = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .multipart("/api/v1/files")
                    .file(new org.springframework.mock.web.MockMockMultipartFileFix()))
            .andReturn().getResponse().getContentAsString();
        throw new IllegalStateException("не используется");
    }

    private void runDue() throws Exception {
        mockMvc.perform(post("/api/v1/internal/demo/jobs/run-due")
                .header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isOk());
    }

    private UUID adminId() {
        return jdbcTemplate.queryForObject(
            "select id from identity.app_user where email_normalized = ?",
            UUID.class, "admin@plantarena.local");
    }

    private UUID newUser(String email, String displayName) throws Exception {
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

**ВНИМАНИЕ (исправить при создании файла):** метод `uploadGreen` выше содержит ошибочный фрагмент — реализовать по образцу `FeedApiIT.uploadAs`:

```java
    private UUID uploadGreen(UUID user) throws Exception {
        byte[] content;
        try (InputStream in = getClass().getResourceAsStream("/media/reference/green-8x8.png")) {
            assertThat(in).isNotNull();
            content = in.readAllBytes();
        }
        String response = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .multipart("/api/v1/files")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "reference.png",
                    MediaType.IMAGE_PNG_VALUE, content))
                .header(DEMO_HEADER, user.toString()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }
```

- [ ] **Step 6: Запустить — красный**

```bash
./mvnw -q verify -Dit.test='GuestSessionsApiIT,FeedApiIT,FeedCursorApiIT' -DfailIfNoTests=false
```

Ожидание: unit-тесты PASS; все три IT FAIL: `POST /api/v1/guest-sessions` и `GET /api/v1/feed` — 404 (ручек нет). Это честный красный внешнего цикла.

- [ ] **Step 7: Commit**

```bash
git add docs/specs/2026-09-28-iteration-8-feed-design.md \
  src/main/java/com/plantarena/feed/application/port/out/ \
  src/test/java/com/plantarena/tournaments/GuestSessionsApiIT.java \
  src/test/java/com/plantarena/feed/FeedApiIT.java \
  src/test/java/com/plantarena/feed/FeedCursorApiIT.java
git commit -m "feat(feed): порты out VotingDirectory/GuestSessions/PlantCatalog/OwnerDirectory, красные приёмочные GuestSessionsApiIT/FeedApiIT/FeedCursorApiIT"
```

---

### Task 2: Домен — `GuestSession`, `VotingSubject.guest`

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/domain/GuestSession.java`
- Create: `src/main/java/com/plantarena/tournaments/domain/GuestSessionRepository.java`
- Modify: `src/main/java/com/plantarena/tournaments/domain/VotingSubject.java`
- Test: `src/test/java/com/plantarena/tournaments/domain/GuestSessionTest.java`
- Test: `src/test/java/com/plantarena/tournaments/domain/VotingSubjectTest.java`

**Interfaces:**
- Consumes: ничего (чистый Java, без Spring/JPA — правило 10.2.5).
- Produces (для Tasks 3–4, 7, 9): агрегат `GuestSession.issue(UUID id, String tokenHash, Instant now, Duration ttl)` / `restore(UUID id, String tokenHash, Instant createdAt, Instant expiresAt)` / `boolean isActive(Instant now)` + геттеры `id()`, `tokenHash()`, `createdAt()`, `expiresAt()`; порт `GuestSessionRepository { GuestSession save(GuestSession); Optional<GuestSession> findByTokenHash(String tokenHash); }`; `VotingSubject.guest(UUID sessionId)` → `subjectKey() = "GUEST:" + sessionId`, `isUser(...) = false`, `sessionId()`.

- [ ] **Step 1: Красные доменные тесты**

`src/test/java/com/plantarena/tournaments/domain/GuestSessionTest.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Гостевая сессия (раздел 9): хранится только хэш токена, срок действия. */
@DisplayName("GuestSession: хэш токена и срок действия")
class GuestSessionTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final String HASH_64 = "a".repeat(64);

    @Test
    @DisplayName("выдача: хэш и срок now + ttl; токена в агрегате нет вообще")
    void выдача_сессии() {
        UUID id = UUID.randomUUID();
        GuestSession session = GuestSession.issue(id, HASH_64, NOW, Duration.ofHours(24));
        assertThat(session.id()).isEqualTo(id);
        assertThat(session.tokenHash()).isEqualTo(HASH_64);
        assertThat(session.createdAt()).isEqualTo(NOW);
        assertThat(session.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("активна, пока now < expiresAt; в expiresAt — уже нет (полуинтервал, как окна)")
    void активность() {
        GuestSession session = GuestSession.issue(UUID.randomUUID(), HASH_64, NOW,
            Duration.ofHours(1));
        assertThat(session.isActive(NOW)).isTrue();
        assertThat(session.isActive(NOW.plus(Duration.ofMinutes(59)))).isTrue();
        assertThat(session.isActive(session.expiresAt())).isFalse();
        assertThat(session.isActive(NOW.plus(Duration.ofHours(2)))).isFalse();
    }

    @Test
    @DisplayName("некорректные аргументы: пустой хэш, неположительный ttl")
    void валидация() {
        assertThatThrownBy(() -> GuestSession.issue(UUID.randomUUID(), "", NOW,
            Duration.ofHours(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuestSession.issue(UUID.randomUUID(), HASH_64, NOW,
            Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuestSession.issue(UUID.randomUUID(), HASH_64, NOW,
            Duration.ofHours(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
```

`src/test/java/com/plantarena/tournaments/domain/VotingSubjectTest.java`:

```java
package com.plantarena.tournaments.domain;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Субъект голосования (раздел 9): USER и GUEST, стабильный subjectKey. */
@DisplayName("VotingSubject: USER и GUEST")
class VotingSubjectTest {

    @Test
    @DisplayName("гость: ключ GUEST:<sessionId>, самоголосование неприменимо")
    void гостевой_субъект() {
        UUID sessionId = UUID.randomUUID();
        VotingSubject guest = VotingSubject.guest(sessionId);
        assertThat(guest.subjectKey()).isEqualTo("GUEST:" + sessionId);
        assertThat(guest.isUser(UUID.randomUUID())).isFalse();
        assertThat(guest.sessionId()).isEqualTo(sessionId);
        assertThat(guest.userId()).isNull();
    }

    @Test
    @DisplayName("пользователь: ключ USER:<userId>, гость — не он")
    void пользовательский_субъект() {
        UUID userId = UUID.randomUUID();
        VotingSubject user = VotingSubject.user(userId);
        assertThat(user.subjectKey()).isEqualTo("USER:" + userId);
        assertThat(user.isUser(userId)).isTrue();
        assertThat(user.isUser(UUID.randomUUID())).isFalse();
        assertThat(user.sessionId()).isNull();
    }

    @Test
    @DisplayName("ключи USER и GUEST не конфликтуют (разные префиксы)")
    void префиксы_ключей() {
        UUID id = UUID.randomUUID();
        assertThat(VotingSubject.user(id).subjectKey())
            .isNotEqualTo(VotingSubject.guest(id).subjectKey());
    }
}
```

- [ ] **Step 2: Запустить — красный**

```bash
./mvnw -q test -Dtest='GuestSessionTest,VotingSubjectTest' -DfailIfNoTests=false
```

Ожидание: FAIL — `GuestSession` и `VotingSubject.guest` не существуют (ошибка компиляции).

- [ ] **Step 3: Реализация**

`src/main/java/com/plantarena/tournaments/domain/GuestSession.java`:

```java
package com.plantarena.tournaments.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Гостевая сессия (раздел 9): анонимный субъект голосования глобальных окон.
 * Токен выдаётся клиенту один раз и в агрегате не существует — только хэш
 * (SHA-256 hex). Сессия неизменяема после создания (без version): повторная
 * выдача — новая сессия. Активна, пока now < expiresAt (полуинтервал).
 */
public final class GuestSession {

    private final UUID id;
    private final String tokenHash;
    private final Instant createdAt;
    private final Instant expiresAt;

    private GuestSession(UUID id, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        if (this.tokenHash.isBlank()) {
            throw new IllegalArgumentException("tokenHash не может быть пустым");
        }
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!this.createdAt.isBefore(this.expiresAt)) {
            throw new IllegalArgumentException("expiresAt должен быть позже createdAt");
        }
    }

    public static GuestSession issue(UUID id, String tokenHash, Instant now, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl должен быть положительным");
        }
        return new GuestSession(id, tokenHash, now, now.plus(ttl));
    }

    /** Восстановление из хранилища (использует только persistence-адаптер). */
    public static GuestSession restore(UUID id, String tokenHash, Instant createdAt,
                                       Instant expiresAt) {
        return new GuestSession(id, tokenHash, createdAt, expiresAt);
    }

    /** Активна, пока now < expiresAt (полуинтервал, как окна голосования). */
    public boolean isActive(Instant now) {
        return now.isBefore(expiresAt);
    }

    public UUID id() {
        return id;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
```

`src/main/java/com/plantarena/tournaments/domain/GuestSessionRepository.java`:

```java
package com.plantarena.tournaments.domain;

import java.util.Optional;
import java.util.UUID;

/** Порт репозитория агрегата GuestSession (раздел 9). */
public interface GuestSessionRepository {

    GuestSession save(GuestSession session);

    /** Сессия по хэшу токена (разрешение гостя по заголовку X-Guest-Token). */
    Optional<GuestSession> findByTokenHash(String tokenHash);
}
```

`src/main/java/com/plantarena/tournaments/domain/VotingSubject.java` — заменить целиком:

```java
package com.plantarena.tournaments.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Субъект голосования (раздел 9): USER(userId) или GUEST(гостевая сессия).
 * subjectKey — стабильный ключ уникальности (окно, entry, субъект) с
 * префиксом типа: «USER:<uuid>» / «GUEST:<sessionId>». Гость не владеет
 * участиями — самоголосование (допущение 6) к нему неприменимо.
 */
public final class VotingSubject {

    private final UUID userId;     // USER
    private final UUID sessionId;  // GUEST

    private VotingSubject(UUID userId, UUID sessionId) {
        if (userId == null && sessionId == null) {
            throw new IllegalArgumentException("Субъект без идентификатора");
        }
        this.userId = userId;
        this.sessionId = sessionId;
    }

    public static VotingSubject user(UUID userId) {
        return new VotingSubject(Objects.requireNonNull(userId, "userId"), null);
    }

    public static VotingSubject guest(UUID sessionId) {
        return new VotingSubject(null, Objects.requireNonNull(sessionId, "sessionId"));
    }

    /** Ключ уникальности голоса (хранится в БД как VARCHAR). */
    public String subjectKey() {
        return userId != null ? "USER:" + userId : "GUEST:" + sessionId;
    }

    /** Самоголосование (допущение 6): субъект — владелец участия? Гость — нет. */
    public boolean isUser(UUID candidate) {
        return userId != null && userId.equals(candidate);
    }

    public UUID userId() {
        return userId;
    }

    public UUID sessionId() {
        return sessionId;
    }
}
```

- [ ] **Step 4: Запустить — зелёный, затем весь verify**

```bash
./mvnw -q test -Dtest='GuestSessionTest,VotingSubjectTest' -DfailIfNoTests=false
./mvnw -q verify
```

Ожидание: оба PASS; verify зелёный (существующие тесты `VotingSubject` используют только `user(...)` — совместимо).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/plantarena/tournaments/domain/GuestSession.java \
  src/main/java/com/plantarena/tournaments/domain/GuestSessionRepository.java \
  src/main/java/com/plantarena/tournaments/domain/VotingSubject.java \
  src/test/java/com/plantarena/tournaments/domain/GuestSessionTest.java \
  src/test/java/com/plantarena/tournaments/domain/VotingSubjectTest.java
git commit -m "feat(guests): домен GuestSession (хэш токена, срок) и VotingSubject.guest"
```

---

### Task 3: Persistence `guest_session` — миграция V5, JPA, контракт

**Files:**
- Create: `src/main/resources/db/migration/tournaments/V5__guest_sessions.sql`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/GuestSessionJpaEntity.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/GuestSessionJpaRepository.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaGuestSessionRepository.java`
- Test: `src/test/java/com/plantarena/tournaments/GuestSessionRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/tournaments/adapter/out/persistence/JpaGuestSessionRepositoryContractIT.java`
- Test: `src/test/java/com/plantarena/tournaments/application/support/InMemoryGuestSessionRepository.java`
- Test: `src/test/java/com/plantarena/tournaments/application/support/InMemoryGuestSessionRepositoryContractTest.java`

**Interfaces:**
- Consumes: `GuestSession`, `GuestSessionRepository` (Task 2).
- Produces (для Tasks 4, 7, 9): JPA-реализация порта; `InMemoryGuestSessionRepository` (фейк для application-тестов: `save`/`findByTokenHash`, хранит по tokenHash).

- [ ] **Step 1: Миграция**

`src/main/resources/db/migration/tournaments/V5__guest_sessions.sql`:

```sql
-- Контекст tournaments: гостевые сессии (разделы 9, 11). Хранится только
-- хэш токена (SHA-256 hex, 64 символа) и срок действия; токен выдаётся
-- клиенту один раз и нигде не сохраняется. Агрегат неизменяем — без version.
CREATE TABLE guest_session (
    id         UUID         PRIMARY KEY,
    token_hash VARCHAR(64)  NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT guest_session_interval_chk CHECK (created_at < expires_at)
);
CREATE INDEX guest_session_expires_idx ON guest_session (expires_at);
```

- [ ] **Step 2: Красный контрактный тест**

`src/test/java/com/plantarena/tournaments/GuestSessionRepositoryContractTest.java`:

```java
package com.plantarena.tournaments;

import com.plantarena.tournaments.domain.GuestSession;
import com.plantarena.tournaments.domain.GuestSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт репозитория GuestSession: фейк и JPA-адаптер ведут себя одинаково
 * (честность фейка из application-тестов). @Transactional на классе — урок
 * итерации 2.
 */
@Transactional
@DisplayName("Контракт GuestSessionRepository: save + findByTokenHash")
public abstract class GuestSessionRepositoryContractTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    protected abstract GuestSessionRepository repository();

    @Test
    @DisplayName("save + findByTokenHash: сессия восстанавливается по хэшу")
    void roundtrip_по_хэшу() {
        UUID id = UUID.randomUUID();
        String hash = "b".repeat(64);
        repository().save(GuestSession.issue(id, hash, NOW, Duration.ofHours(24)));

        GuestSession restored = repository().findByTokenHash(hash).orElseThrow();

        assertThat(restored.id()).isEqualTo(id);
        assertThat(restored.tokenHash()).isEqualTo(hash);
        assertThat(restored.createdAt()).isEqualTo(NOW);
        assertThat(restored.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(restored.isActive(NOW)).isTrue();
    }

    @Test
    @DisplayName("неизвестный хэш — пусто; хэши не коллидируют")
    void неизвестный_хэш() {
        repository().save(GuestSession.issue(UUID.randomUUID(), "c".repeat(64), NOW,
            Duration.ofHours(1)));
        assertThat(repository().findByTokenHash("d".repeat(64))).isEmpty();
    }
}
```

- [ ] **Step 3: Запустить — красный**

```bash
./mvnw -q test -Dtest='GuestSessionRepositoryContractTest*' -DfailIfNoTests=false
```

Ожидание: FAIL (нет наследников/реализаций — или компиляция, если наследники ещё не созданы; создать наследники в Step 4 вместе с реализацией).

- [ ] **Step 4: JPA-реализация и наследники контракта**

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/GuestSessionJpaEntity.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA-модель гостевой сессии (раздел 11); маппинг в домен — явный. */
@Entity
@Table(name = "guest_session", schema = "tournaments")
public class GuestSessionJpaEntity {

    @Id
    private UUID id;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    UUID getId() {
        return id;
    }

    String getTokenHash() {
        return tokenHash;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    void setId(UUID id) {
        this.id = id;
    }

    void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/GuestSessionJpaRepository.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface GuestSessionJpaRepository extends JpaRepository<GuestSessionJpaEntity, UUID> {

    Optional<GuestSessionJpaEntity> findByTokenHash(String tokenHash);
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaGuestSessionRepository.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.domain.GuestSession;
import com.plantarena.tournaments.domain.GuestSessionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA-реализация порта GuestSessionRepository: явный маппинг. */
@Repository
public class JpaGuestSessionRepository implements GuestSessionRepository {

    private final GuestSessionJpaRepository jpaRepository;

    public JpaGuestSessionRepository(GuestSessionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public GuestSession save(GuestSession session) {
        GuestSessionJpaEntity entity = new GuestSessionJpaEntity();
        entity.setId(session.id());
        entity.setTokenHash(session.tokenHash());
        entity.setCreatedAt(session.createdAt());
        entity.setExpiresAt(session.expiresAt());
        jpaRepository.saveAndFlush(entity);
        return session;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GuestSession> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash)
            .map(entity -> GuestSession.restore(entity.getId(), entity.getTokenHash(),
                entity.getCreatedAt(), entity.getExpiresAt()));
    }
}
```

`src/test/java/com/plantarena/tournaments/application/support/InMemoryGuestSessionRepository.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.domain.GuestSession;
import com.plantarena.tournaments.domain.GuestSessionRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Фейк репозитория гостевых сессий для application-тестов. */
public class InMemoryGuestSessionRepository implements GuestSessionRepository {

    private final Map<String, GuestSession> byTokenHash = new ConcurrentHashMap<>();

    @Override
    public GuestSession save(GuestSession session) {
        byTokenHash.put(session.tokenHash(), session);
        return session;
    }

    @Override
    public Optional<GuestSession> findByTokenHash(String tokenHash) {
        return Optional.ofNullable(byTokenHash.get(tokenHash));
    }
}
```

`src/test/java/com/plantarena/tournaments/application/support/InMemoryGuestSessionRepositoryContractTest.java`:

```java
package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.GuestSessionRepositoryContractTest;
import com.plantarena.tournaments.domain.GuestSessionRepository;

/** In-memory-наследник контракта GuestSessionRepository. */
class InMemoryGuestSessionRepositoryContractTest extends GuestSessionRepositoryContractTest {

    private final InMemoryGuestSessionRepository repository = new InMemoryGuestSessionRepository();

    @Override
    protected GuestSessionRepository repository() {
        return repository;
    }
}
```

`src/test/java/com/plantarena/tournaments/adapter/out/persistence/JpaGuestSessionRepositoryContractIT.java`:

```java
package com.plantarena.tournaments.adapter.out.persistence;

import com.plantarena.tournaments.GuestSessionRepositoryContractTest;
import com.plantarena.tournaments.domain.GuestSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/** JPA-наследник контракта GuestSessionRepository на Testcontainers. */
@DataJpaTest
@Import(JpaGuestSessionRepository.class)
class JpaGuestSessionRepositoryContractIT extends GuestSessionRepositoryContractTest {

    @Autowired
    private JpaGuestSessionRepository repository;

    @Override
    protected GuestSessionRepository repository() {
        return repository;
    }
}
```

Примечание: если существующие JPA-контрактные IT используют иной базовый класс/аннотации (например, `AbstractIntegrationTest` вместо `@DataJpaTest`) — следовать их паттерну (`JpaQualificationEpochRepositoryContractIT`).

- [ ] **Step 5: Запустить — зелёный, затем весь verify**

```bash
./mvnw -q test -Dtest='GuestSessionRepositoryContractTest*' -DfailIfNoTests=false
./mvnw -q verify
```

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/tournaments/V5__guest_sessions.sql \
  src/main/java/com/plantarena/tournaments/adapter/out/persistence/GuestSessionJpaEntity.java \
  src/main/java/com/plantarena/tournaments/adapter/out/persistence/GuestSessionJpaRepository.java \
  src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaGuestSessionRepository.java \
  src/test/java/com/plantarena/tournaments/GuestSessionRepositoryContractTest.java \
  src/test/java/com/plantarena/tournaments/adapter/out/persistence/JpaGuestSessionRepositoryContractIT.java \
  src/test/java/com/plantarena/tournaments/application/support/InMemoryGuestSessionRepository.java \
  src/test/java/com/plantarena/tournaments/application/support/InMemoryGuestSessionRepositoryContractTest.java
git commit -m "feat(guests): миграция V5 guest_session, JPA-адаптер и контрактные тесты"
```

---

### Task 4: Гостевые сессии — application, REST, 429

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/application/GuestTokens.java`
- Create: `src/main/java/com/plantarena/tournaments/application/GuestSessionsSettings.java`
- Create: `src/main/java/com/plantarena/tournaments/application/FixedWindowRateLimiter.java`
- Create: `src/main/java/com/plantarena/tournaments/application/RateLimitExceededException.java`
- Create: `src/main/java/com/plantarena/tournaments/application/GuestSessionService.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/in/CreateGuestSessionUseCase.java`
- Create: `src/main/java/com/plantarena/tournaments/application/port/out/AbuseSignals.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/GuestSessionController.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/in/web/GuestSessionResponse.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/abuse/LoggingAbuseSignals.java`
- Modify: `src/main/java/com/plantarena/tournaments/adapter/in/web/TournamentsExceptionHandler.java`
- Modify: `src/main/java/com/plantarena/config/TournamentsWiringConfig.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/plantarena/tournaments/application/GuestSessionServiceTest.java`

**Interfaces:**
- Consumes: `GuestSession`, `GuestSessionRepository` (Tasks 2–3), `Clock`, `AbstractIntegrationTest`.
- Produces (для Tasks 7, 9): `GuestTokens { static String newToken(); static String sha256Hex(String raw); }`; `GuestSessionsSettings(Duration sessionTtl, int sessionCreationLimitPerMinute, int voteLimitPerMinute)` (`plantarena.guests.*`); `FixedWindowRateLimiter { void check(String key, int limit); }` (бин); `RateLimitExceededException(String message, long retryAfterSeconds)` + `retryAfterSeconds()`; `CreateGuestSessionUseCase { GuestSessionIssued issue(String clientIp); record GuestSessionIssued(String token, Instant expiresAt) }`; порт `AbuseSignals { void signal(String action, String details); }`; REST `POST /api/v1/guest-sessions` → 201 `{token, expiresAt}` / 429 `RATE_LIMITED` + `Retry-After`.

- [ ] **Step 1: Красный application-тест**

`src/test/java/com/plantarena/tournaments/application/GuestSessionServiceTest.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.tournaments.application.port.in.CreateGuestSessionUseCase;
import com.plantarena.tournaments.application.support.InMemoryGuestSessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Выдача гостевых сессий (раздел 9): токен один раз, в репозитории — только
 * хэш; лимит выдачи по IP — 429 с retryAfter; сигнал о превышении — в AbuseSignals.
 */
@DisplayName("GuestSessionService: токен, хэш, лимит 429")
class GuestSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private final InMemoryGuestSessionRepository sessions = new InMemoryGuestSessionRepository();
    private final FixedWindowRateLimiter rateLimiter = new FixedWindowRateLimiter(
        Clock.fixed(NOW, ZoneOffset.UTC));
    private final GuestSessionsSettings settings = new GuestSessionsSettings(
        Duration.ofHours(24), 2, 30);
    private final RecordingAbuseSignals abuseSignals = new RecordingAbuseSignals();
    private final GuestSessionService service = new GuestSessionService(sessions, settings,
        rateLimiter, abuseSignals, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("выдача: токен и expiresAt наружу; в репозитории — только хэш токена")
    void выдача_токена() {
        CreateGuestSessionUseCase.GuestSessionIssued issued = service.issue("127.0.0.1");

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(sessions.findByTokenHash(GuestTokens.sha256Hex(issued.token())))
            .as("в репозитории только хэш, не токен")
            .isPresent();
    }

    @Test
    @DisplayName("токены непредсказуемы: две выдачи — разные токены и хэши")
    void непредсказуемость() {
        String token1 = service.issue("127.0.0.1").token();
        String token2 = service.issue("127.0.0.1").token();
        assertThat(token1).isNotEqualTo(token2);
        assertThat(GuestTokens.sha256Hex(token1)).isNotEqualTo(GuestTokens.sha256Hex(token2));
    }

    @Test
    @DisplayName("лимит выдачи на IP: третий в минуту — RateLimitExceededException с retryAfter")
    void лимит_выдачи() {
        service.issue("127.0.0.1");
        service.issue("127.0.0.1");

        assertThatThrownBy(() -> service.issue("127.0.0.1"))
            .isInstanceOf(RateLimitExceededException.class)
            .satisfies(e -> assertThat(((RateLimitExceededException) e).retryAfterSeconds())
                .isBetween(1L, 60L));
        assertThat(abuseSignals.lastAction).isEqualTo("guest-session-limit");

        // другой IP — своя корзина
        assertThat(service.issue("192.168.0.1").token()).isNotBlank();
    }

    private static final class RecordingAbuseSignals
            implements com.plantarena.tournaments.application.port.out.AbuseSignals {

        private String lastAction;

        @Override
        public void signal(String action, String details) {
            this.lastAction = action;
        }
    }
}
```

- [ ] **Step 2: Запустить — красный**

```bash
./mvnw -q test -Dtest='GuestSessionServiceTest' -DfailIfNoTests=false
```

Ожидание: FAIL — классы не существуют (компиляция).

- [ ] **Step 3: Реализация**

`src/main/java/com/plantarena/tournaments/application/GuestTokens.java`:

```java
package com.plantarena.tournaments.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Токены гостевых сессий (раздел 9): 32 байта SecureRandom → Base64URL
 * (непрогнозируемый) и SHA-256 hex для хранения. Токен существует только
 * в ответе выдачи и в заголовке X-Guest-Token.
 */
public final class GuestTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private GuestTokens() {
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
```

`src/main/java/com/plantarena/tournaments/application/GuestSessionsSettings.java`:

```java
package com.plantarena.tournaments.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Гостевые сессии (раздел 9): срок действия и минимальные лимиты защиты
 * от накрутки (in-memory, сбрасываются рестартом — ADR-013).
 */
@ConfigurationProperties(prefix = "plantarena.guests")
public record GuestSessionsSettings(Duration sessionTtl, int sessionCreationLimitPerMinute,
                                    int voteLimitPerMinute) {

    public GuestSessionsSettings {
        if (sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("plantarena.guests.session-ttl > 0");
        }
        if (sessionCreationLimitPerMinute < 1 || voteLimitPerMinute < 1) {
            throw new IllegalArgumentException("plantarena.guests.*-limit-per-minute >= 1");
        }
    }
}
```

`src/main/java/com/plantarena/tournaments/application/RateLimitExceededException.java`:

```java
package com.plantarena.tournaments.application;

/**
 * Превышен лимит защиты от накрутки (раздел 9): 429 RATE_LIMITED +
 * заголовок Retry-After (секунды до конца фиксированного окна).
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
```

`src/main/java/com/plantarena/tournaments/application/FixedWindowRateLimiter.java`:

```java
package com.plantarena.tournaments.application;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Минимальная in-memory защита от накрутки (раздел 9): фиксированное окно
 * 1 минута на ключ (IP выдачи сессий / сессия голосования). Состояние
 * сбрасывается рестартом — осознанная минимальность (ADR-013);
 * полноценный anti-doping-service — будущая работа.
 */
public class FixedWindowRateLimiter {

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private record Window(long minute, AtomicInteger count) {
    }

    public FixedWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Превышение лимита в текущем окне — RateLimitExceededException. */
    public void check(String key, int limit) {
        long minute = clock.instant().toEpochMilli() / 60_000;
        Window window = windows.compute(key, (k, old) ->
            old == null || old.minute() != minute
                ? new Window(minute, new AtomicInteger())
                : old);
        if (window.count().incrementAndGet() > limit) {
            long retryAfterSeconds = 60 - clock.instant().getEpochSecond() % 60;
            throw new RateLimitExceededException(
                "Превышен лимит (" + limit + "/мин), повторите через " + retryAfterSeconds + " с",
                retryAfterSeconds);
        }
    }
}
```

`src/main/java/com/plantarena/tournaments/application/port/in/CreateGuestSessionUseCase.java`:

```java
package com.plantarena.tournaments.application.port.in;

import java.time.Instant;

/** Выдача гостевой сессии (раздел 9): публичная ручка, токен один раз. */
public interface CreateGuestSessionUseCase {

    GuestSessionIssued issue(String clientIp);

    record GuestSessionIssued(String token, Instant expiresAt) {
    }
}
```

`src/main/java/com/plantarena/tournaments/application/port/out/AbuseSignals.java`:

```java
package com.plantarena.tournaments.application.port.out;

/**
 * Журнал подозрительных действий (раздел 9): выходной порт; в лабе №1 —
 * логирующий адаптер, в целевой системе — anti-doping-service.
 */
public interface AbuseSignals {

    void signal(String action, String details);
}
```

`src/main/java/com/plantarena/tournaments/application/GuestSessionService.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.tournaments.application.port.in.CreateGuestSessionUseCase;
import com.plantarena.tournaments.application.port.out.AbuseSignals;
import com.plantarena.tournaments.domain.GuestSession;
import com.plantarena.tournaments.domain.GuestSessionRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Выдача гостевых сессий (раздел 9): публично, токен возвращается один раз;
 * в БД — только SHA-256 хэш. Лимит выдачи — на IP (фиксированное окно),
 * превышение — 429 + сигнал в AbuseSignals.
 */
@Service
public class GuestSessionService implements CreateGuestSessionUseCase {

    private final GuestSessionRepository sessions;
    private final GuestSessionsSettings settings;
    private final FixedWindowRateLimiter rateLimiter;
    private final AbuseSignals abuseSignals;
    private final Clock clock;

    public GuestSessionService(GuestSessionRepository sessions, GuestSessionsSettings settings,
                               FixedWindowRateLimiter rateLimiter, AbuseSignals abuseSignals,
                               Clock clock) {
        this.sessions = sessions;
        this.settings = settings;
        this.rateLimiter = rateLimiter;
        this.abuseSignals = abuseSignals;
        this.clock = clock;
    }

    @Override
    @Transactional
    public GuestSessionIssued issue(String clientIp) {
        String ip = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        try {
            rateLimiter.check("guest-session:" + ip, settings.sessionCreationLimitPerMinute());
        } catch (RateLimitExceededException e) {
            abuseSignals.signal("guest-session-limit", "ip=" + ip);
            throw e;
        }
        String token = GuestTokens.newToken();
        GuestSession session = GuestSession.issue(UUID.randomUUID(),
            GuestTokens.sha256Hex(token), clock.instant(), settings.sessionTtl());
        sessions.save(session);
        return new GuestSessionIssued(token, session.expiresAt());
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/out/abuse/LoggingAbuseSignals.java`:

```java
package com.plantarena.tournaments.adapter.out.abuse;

import com.plantarena.tournaments.application.port.out.AbuseSignals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Логирующий адаптер журнала подозрительных действий (раздел 9). */
@Component
public class LoggingAbuseSignals implements AbuseSignals {

    private static final Logger log = LoggerFactory.getLogger(LoggingAbuseSignals.class);

    @Override
    public void signal(String action, String details) {
        log.warn("Подозрительное действие: {} ({})", action, details);
    }
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/GuestSessionResponse.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import java.time.Instant;

/** Ответ POST /guest-sessions: токен возвращается ровно один раз (раздел 9). */
public record GuestSessionResponse(String token, Instant expiresAt) {
}
```

`src/main/java/com/plantarena/tournaments/adapter/in/web/GuestSessionController.java`:

```java
package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.application.port.in.CreateGuestSessionUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Гостевые сессии (раздел 13): публичная выдача. Токен передаётся далее в
 * заголовке X-Guest-Token; гость голосует только в глобальных окнах.
 */
@RestController
@Tag(name = "guest-sessions")
public class GuestSessionController {

    private final CreateGuestSessionUseCase createGuestSession;

    public GuestSessionController(CreateGuestSessionUseCase createGuestSession) {
        this.createGuestSession = createGuestSession;
    }

    @PostMapping("/api/v1/guest-sessions")
    @Operation(operationId = "guest-sessions-create",
        summary = "Создать гостевую сессию: токен возвращается один раз (429 при лимите)")
    public ResponseEntity<GuestSessionResponse> create(HttpServletRequest request) {
        CreateGuestSessionUseCase.GuestSessionIssued issued =
            createGuestSession.issue(request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new GuestSessionResponse(issued.token(), issued.expiresAt()));
    }
}
```

- [ ] **Step 4: 429 в `TournamentsExceptionHandler`**

В `src/main/java/com/plantarena/tournaments/adapter/in/web/TournamentsExceptionHandler.java` добавить импорты:

```java
import com.plantarena.tournaments.application.RateLimitExceededException;
import org.springframework.http.HttpHeaders;
```

и обработчик перед приватным `respond`:

```java
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> rateLimited(RateLimitExceededException e,
                                                HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
            .body(new ApiError(URI.create("about:blank"), HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                HttpStatus.TOO_MANY_REQUESTS.value(), e.getMessage(),
                URI.create(request.getRequestURI()), "RATE_LIMITED", List.of(), traceId));
    }
```

- [ ] **Step 5: Wiring и конфигурация**

`src/main/java/com/plantarena/config/TournamentsWiringConfig.java` — заменить целиком:

```java
package com.plantarena.config;

import com.plantarena.tournaments.application.FixedWindowRateLimiter;
import com.plantarena.tournaments.application.GlobalCompetitionSettings;
import com.plantarena.tournaments.application.GuestSessionsSettings;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Связывание контекста tournaments: глобальный турнир, гости, лимиты. */
@Configuration
@EnableConfigurationProperties({GlobalCompetitionSettings.class, GuestSessionsSettings.class})
public class TournamentsWiringConfig {

    /** Минимальная in-memory защита (раздел 9): фиксированное окно 1 минута. */
    @Bean
    public FixedWindowRateLimiter guestRateLimiter(Clock clock) {
        return new FixedWindowRateLimiter(clock);
    }
}
```

`src/main/resources/application.yml` — в блок `plantarena:` добавить:

```yaml
  guests:
    session-ttl: 24h # срок действия гостевой сессии (раздел 9)
    session-creation-limit-per-minute: 10 # лимит выдачи сессий на IP (429, ADR-013)
    vote-limit-per-minute: 30 # лимит голосов гостя на сессию (429, ADR-013)
```

- [ ] **Step 6: Запустить — зелёные unit, затем зелёный GuestSessionsApiIT и весь verify**

```bash
./mvnw -q test -Dtest='GuestSessionServiceTest' -DfailIfNoTests=false
./mvnw -q verify -Dit.test='GuestSessionsApiIT' -DfailIfNoTests=false
./mvnw -q verify
```

Ожидание: `GuestSessionsApiIT` зелёный (201 + 429 с `Retry-After`); весь verify зелёный.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/plantarena/tournaments/application/GuestTokens.java \
  src/main/java/com/plantarena/tournaments/application/GuestSessionsSettings.java \
  src/main/java/com/plantarena/tournaments/application/FixedWindowRateLimiter.java \
  src/main/java/com/plantarena/tournaments/application/RateLimitExceededException.java \
  src/main/java/com/plantarena/tournaments/application/GuestSessionService.java \
  src/main/java/com/plantarena/tournaments/application/port/in/CreateGuestSessionUseCase.java \
  src/main/java/com/plantarena/tournaments/application/port/out/AbuseSignals.java \
  src/main/java/com/plantarena/tournaments/adapter/in/web/GuestSessionController.java \
  src/main/java/com/plantarena/tournaments/adapter/in/web/GuestSessionResponse.java \
  src/main/java/com/plantarena/tournaments/adapter/in/web/TournamentsExceptionHandler.java \
  src/main/java/com/plantarena/tournaments/adapter/out/abuse/LoggingAbuseSignals.java \
  src/main/java/com/plantarena/config/TournamentsWiringConfig.java \
  src/main/resources/application.yml \
  src/test/java/com/plantarena/tournaments/application/GuestSessionServiceTest.java
git commit -m "feat(guests): выдача сессий (токен один раз, хэш в БД), лимит 429 + Retry-After, REST /guest-sessions"
```

---

### Task 5: События `VotingWindowOpened`/`VotingWindowClosed` и их публикация

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/api/event/VotingWindowOpenedEvent.java`
- Create: `src/main/java/com/plantarena/tournaments/api/event/VotingWindowClosedEvent.java`
- Modify: `src/main/java/com/plantarena/tournaments/application/StartTournamentService.java`
- Modify: `src/main/java/com/plantarena/tournaments/application/CloseVotingWindowService.java`
- Modify: `src/main/java/com/plantarena/tournaments/application/AdvanceGlobalCompetitionService.java`
- Test: `src/test/java/com/plantarena/tournaments/application/StartTournamentServiceTest.java` (дополнение)
- Test: `src/test/java/com/plantarena/tournaments/application/CloseVotingWindowServiceTest.java` (дополнение)
- Test: `src/test/java/com/plantarena/tournaments/application/AdvanceGlobalCompetitionServiceTest.java` (дополнение)

**Interfaces:**
- Consumes: конверт `IntegrationEvent` (shared.event), `VotingWindow` (геттеры `scope()`, `sequence()`, `epochId()`, `clusterId()`, `clusterKey()`, `opensAt()`, `closesAt()`, `version()`), `IntegrationEventPublisher`.
- Produces (для Task 8): `VotingWindowOpenedEvent.Payload(UUID windowId, UUID tournamentId, String scope, int sequence, UUID epochId, UUID clusterId, String clusterKey, Instant opensAt, Instant closesAt, List<Participant> participants)` + `Participant(UUID entryId, UUID userId, UUID plantId, Instant joinedAt)`; `VotingWindowClosedEvent.Payload(UUID windowId, UUID tournamentId, String scope, int sequence)`; TYPE = `"VotingWindowOpened"` / `"VotingWindowClosed"`, SCHEMA_VERSION = 1.

- [ ] **Step 1: События (по образцу `TournamentStartedEvent`)**

`src/main/java/com/plantarena/tournaments/api/event/VotingWindowOpenedEvent.java`:

```java
package com.plantarena.tournaments.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Опубликованное событие: открыто окно голосования с зафиксированным
 * составом (раздел 9). Подписчик — проекция ленты feed (итерация 8);
 * в лабе №4 доставляется через outbox → Kafka. Несёт состав участников
 * (entry, владелец, растение) — feed обогащает карточки сам через
 * read-контракты plants/identity.
 */
public record VotingWindowOpenedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "VotingWindowOpened";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID windowId, UUID tournamentId, String scope, int sequence,
                          UUID epochId, UUID clusterId, String clusterKey,
                          Instant opensAt, Instant closesAt,
                          List<Participant> participants) {
    }

    public record Participant(UUID entryId, UUID userId, UUID plantId, Instant joinedAt) {
    }
}
```

`src/main/java/com/plantarena/tournaments/api/event/VotingWindowClosedEvent.java`:

```java
package com.plantarena.tournaments.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованное событие: окно голосования закрыто (раздел 9). Подписчик —
 * проекция ленты feed (итерация 8): карточки окна удаляются; выжившие
 * возвращаются событием VotingWindowOpened следующего окна.
 */
public record VotingWindowClosedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "VotingWindowClosed";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID windowId, UUID tournamentId, String scope, int sequence) {
    }
}
```

- [ ] **Step 2: Публикация в трёх сервисах**

`StartTournamentService.java`: импорт `com.plantarena.tournaments.api.event.VotingWindowOpenedEvent`; в `doStart` заменить сохранение первого окна на:

```java
        // первый раунд (раздел 7): состав зафиксирован, счёт с нуля
        VotingWindow firstWindow = VotingWindow.open(tournament.id(), 1,
            admitted.stream()
                .map(entry -> new VotingWindow.ParticipantSeed(entry.id(), entry.userId(),
                    entry.joinedAt()))
                .toList(),
            now, now.plus(tournament.roundDuration()), now);
        windows.save(firstWindow);
        publishOpened(firstWindow, admitted.stream()
            .map(entry -> new VotingWindowOpenedEvent.Participant(entry.id(), entry.userId(),
                entry.plantId(), entry.joinedAt()))
            .toList(), now);
```

и добавить приватные методы:

```java
    /** Проекция ленты (раздел 9): состав первого окна. */
    private void publishOpened(VotingWindow window,
                               List<VotingWindowOpenedEvent.Participant> participants,
                               Instant now) {
        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new VotingWindowOpenedEvent(eventId,
            VotingWindowOpenedEvent.TYPE, VotingWindowOpenedEvent.SCHEMA_VERSION,
            window.id(), window.version(), now, eventId,
            new VotingWindowOpenedEvent.Payload(window.id(), window.tournamentId(),
                window.scope().name(), window.sequence(), window.epochId(), window.clusterId(),
                window.clusterKey(), window.opensAt(), window.closesAt(), participants)));
    }
```

`CloseVotingWindowService.java`: импорты `VotingWindowOpenedEvent`, `VotingWindowClosedEvent`; в `closeOne`:
(1) после `windows.save(window)` (закрытое окно) добавить:

```java
        publishClosed(window, now);
```

(2) ветку следующего раунда заменить на:

```java
        } else {
            List<TournamentEntry> survived = outcome.survivedEntryIds().stream()
                .map(this::findEntry)
                .toList();
            VotingWindow next = VotingWindow.open(tournament.id(), window.sequence() + 1,
                survived.stream()
                    .map(entry -> new VotingWindow.ParticipantSeed(entry.id(), entry.userId(),
                        entry.joinedAt()))
                    .toList(),
                now, now.plus(tournament.roundDuration()), now);
            windows.save(next);
            publishOpened(next, survived.stream()
                .map(entry -> new VotingWindowOpenedEvent.Participant(entry.id(), entry.userId(),
                    entry.plantId(), entry.joinedAt()))
                .toList(), now);
        }
```

и добавить те же приватные `publishOpened(...)` (как в StartTournamentService) и:

```java
    /** Проекция ленты (раздел 9): карточки закрытого окна удаляются. */
    private void publishClosed(VotingWindow window, Instant now) {
        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new VotingWindowClosedEvent(eventId,
            VotingWindowClosedEvent.TYPE, VotingWindowClosedEvent.SCHEMA_VERSION,
            window.id(), window.version(), now, eventId,
            new VotingWindowClosedEvent.Payload(window.id(), window.tournamentId(),
                window.scope().name(), window.sequence())));
    }
```

`AdvanceGlobalCompetitionService.java`: импорты `VotingWindowOpenedEvent`, `VotingWindowClosedEvent`:
(1) `closeQualificationOne` — после `windows.save(window)` (строка `VotingWindow.QualificationCloseOutcome outcome = window.closeQualification(now); windows.save(window);`) добавить `publishClosed(window, now);`
(2) `closeFinalOne` — после `windows.save(window)` добавить `publishClosed(window, now);`
(3) `openNextFinal` — после `windows.save(VotingWindow.openFinal(...))` добавить:

```java
                publishOpened(finalWindow, finalists.stream()
                    .map(entry -> new VotingWindowOpenedEvent.Participant(entry.id(),
                        entry.userId(), entry.plantId(), entry.joinedAt()))
                    .toList(), now);
```

для этого сохранить окно в локальную переменную: `VotingWindow finalWindow = VotingWindow.openFinal(...); windows.save(finalWindow); publishOpened(finalWindow, ..., now);`
(4) `openNextEpoch` — внутри цикла по кластерам после `windows.save(VotingWindow.openQualification(...))` добавить (аналогично через локальную переменную `VotingWindow window`):

```java
                publishOpened(window, clusterEntries.stream()
                    .map(entry -> new VotingWindowOpenedEvent.Participant(entry.id(),
                        entry.userId(), entry.plantId(), entry.joinedAt()))
                    .toList(), now);
```

и добавить приватные `publishOpened`/`publishClosed` (код тот же, что в CloseVotingWindowService).

- [ ] **Step 3: Тесты публикации**

В `StartTournamentServiceTest` добавить тест (фикстуры класса: `service`, `events`, `openTournament`, `readyInvitation`):

```java
    @Test
    @DisplayName("старт публикует VotingWindowOpened с составом первого окна (проекция feed)")
    void старт_публикует_открытие_окна() {
        UUID tournamentId = openTournament(NOW.plusSeconds(1));
        Invitation ready1 = readyInvitation(tournamentId);
        Invitation ready2 = readyInvitation(tournamentId);

        service.startDue(NOW.plusSeconds(2), 10);

        VotingWindowOpenedEvent opened = events.published.stream()
            .filter(VotingWindowOpenedEvent.class::isInstance)
            .map(VotingWindowOpenedEvent.class::cast)
            .findFirst().orElseThrow();
        assertThat(opened.payload().scope()).isEqualTo("PRIVATE");
        assertThat(opened.payload().sequence()).isEqualTo(1);
        assertThat(opened.payload().participants()).hasSize(2);
        assertThat(opened.payload().participants())
            .extracting(VotingWindowOpenedEvent.Participant::userId)
            .containsExactlyInAnyOrder(ready1.userId(), ready2.userId());
        assertThat(opened.payload().participants())
            .allSatisfy(p -> assertThat(p.plantId()).isNotNull());
    }
```

(импорт `com.plantarena.tournaments.api.event.VotingWindowOpenedEvent`).

В `CloseVotingWindowServiceTest` добавить тест, который (по фикстурам класса — существующий сценарий закрытия due-окна) после закрытия утверждает:

```java
        assertThat(events.published).anySatisfy(event ->
            assertThat(event).isInstanceOf(VotingWindowClosedEvent.class));
        VotingWindowClosedEvent closed = events.published.stream()
            .filter(VotingWindowClosedEvent.class::isInstance)
            .map(VotingWindowClosedEvent.class::cast)
            .findFirst().orElseThrow();
        assertThat(closed.payload().windowId()).isEqualTo(windowId);
        assertThat(closed.payload().scope()).isEqualTo("PRIVATE");
```

и для сценария с продолжением турнира (выжившие ≥ 2) утверждает публикацию `VotingWindowOpenedEvent` со `sequence` на 1 больше и составом выживших. Если в классе нет сценария с выжившими — добавить его по образцу существующего (n=3 участника, 0 голосов → выбывает 1, открывается раунд 2).

В `AdvanceGlobalCompetitionServiceTest` добавить утверждения в существующие сценарии (или новые тесты по их образцу):
- закрытие due-квалификации публикует `VotingWindowClosedEvent` со `scope = "QUALIFICATION"` и `windowId` закрытого окна;
- закрытие due-финала публикует `VotingWindowClosedEvent` со `scope = "FINAL"`;
- открытие следующего финала публикует `VotingWindowOpenedEvent` со `scope = "FINAL"` и составом финалистов;
- открытие эпохи публикует по одному `VotingWindowOpenedEvent` со `scope = "QUALIFICATION"` на каждый кластер, с `clusterId`/`clusterKey` и составом `participants` (entryId, userId, plantId, joinedAt).

Формат утверждений — как в коде выше (`events.published.stream().filter(...).map(...)`).

- [ ] **Step 4: Запустить — зелёный, затем весь verify**

```bash
./mvnw -q test -Dtest='StartTournamentServiceTest,CloseVotingWindowServiceTest,AdvanceGlobalCompetitionServiceTest' -DfailIfNoTests=false
./mvnw -q verify
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/plantarena/tournaments/api/event/VotingWindowOpenedEvent.java \
  src/main/java/com/plantarena/tournaments/api/event/VotingWindowClosedEvent.java \
  src/main/java/com/plantarena/tournaments/application/StartTournamentService.java \
  src/main/java/com/plantarena/tournaments/application/CloseVotingWindowService.java \
  src/main/java/com/plantarena/tournaments/application/AdvanceGlobalCompetitionService.java \
  src/test/java/com/plantarena/tournaments/application/StartTournamentServiceTest.java \
  src/test/java/com/plantarena/tournaments/application/CloseVotingWindowServiceTest.java \
  src/test/java/com/plantarena/tournaments/application/AdvanceGlobalCompetitionServiceTest.java
git commit -m "feat(tournaments): события VotingWindowOpened/Closed для проекции ленты (раздел 9)"
```

---

### Task 6: feed — домен read-модели и persistence

**Files:**
- Create: `src/main/java/com/plantarena/feed/domain/FeedCard.java`
- Create: `src/main/java/com/plantarena/feed/domain/FeedCardQuery.java`
- Create: `src/main/java/com/plantarena/feed/domain/FeedCardRepository.java`
- Create: `src/main/java/com/plantarena/feed/domain/FeedCursor.java`
- Create: `src/main/java/com/plantarena/feed/domain/FeedOrdering.java`
- Create: `src/main/resources/db/migration/feed/V2__feed_cards.sql`
- Create: `src/main/java/com/plantarena/feed/adapter/out/persistence/FeedCardJpaEntity.java`
- Create: `src/main/java/com/plantarena/feed/adapter/out/persistence/FeedCardJpaRepository.java`
- Create: `src/main/java/com/plantarena/feed/adapter/out/persistence/JpaFeedCardRepository.java`
- Test: `src/test/java/com/plantarena/feed/FeedCardRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/feed/domain/FeedCursorTest.java`
- Test: `src/test/java/com/plantarena/feed/application/support/InMemoryFeedCardRepository.java`
- Test: `src/test/java/com/plantarena/feed/application/support/InMemoryFeedCardRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/feed/adapter/out/persistence/JpaFeedCardRepositoryContractIT.java`

**Interfaces:**
- Consumes: ничего (чистый домен + JPA).
- Produces (для Task 8): `FeedCard(UUID id, UUID windowId, UUID tournamentId, String scope, UUID clusterId, UUID entryId, UUID userId, UUID plantId, UUID assetId, String title, String ownerDisplayName, Instant joinedAt, Instant closesAt, Instant createdAt, long sortKey)` — `sortKey` заполнен только в результатах `page` (0 при записи); `FeedCardQuery(long seed, Instant snapshotCutoff, Instant now, Set<String> scopes, Set<UUID> participatedTournamentIds, UUID excludedOwnerUserId, Set<UUID> excludedEntryIds, Long lastSortKey, UUID lastId, int limit)`; порт `FeedCardRepository { void saveAll(List<FeedCard> cards); void deleteAllByWindowId(UUID windowId); List<FeedCard> page(FeedCardQuery query); }`; `FeedCursor(long seed, Instant snapshotCutoff, Long lastSortKey, UUID lastId, String subjectKey)`; `FeedOrdering { long newSeed(); }`.

- [ ] **Step 1: Домен read-модели**

`src/main/java/com/plantarena/feed/domain/FeedCard.java`:

```java
package com.plantarena.feed.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Карточка ленты (раздел 9, ADR-002): строка read-модели — снимок участника
 * открытого окна на момент его открытия (title и displayName зафиксированы,
 * смена названий не ретранслируется). sortKey заполнен только в результатах
 * page() — псевдослучайный ключ от seed и стабильного id.
 */
public record FeedCard(
        UUID id,
        UUID windowId,
        UUID tournamentId,
        String scope,
        UUID clusterId,
        UUID entryId,
        UUID userId,
        UUID plantId,
        UUID assetId,
        String title,
        String ownerDisplayName,
        Instant joinedAt,
        Instant closesAt,
        Instant createdAt,
        long sortKey) {
}
```

`src/main/java/com/plantarena/feed/domain/FeedCardQuery.java`:

```java
package com.plantarena.feed.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Запрос страницы ленты (раздел 9): seed и keyset-курсор + повторные фильтры
 * прав. Пустые participatedTournamentIds/excludedEntryIds означают «ничего
 * не совпало» (адаптер подставляет невозможный sentinel, чтобы SQL-IN не
 * пустовал).
 */
public record FeedCardQuery(
        long seed,
        Instant snapshotCutoff,
        Instant now,
        Set<String> scopes,
        Set<UUID> participatedTournamentIds,
        UUID excludedOwnerUserId,
        Set<UUID> excludedEntryIds,
        Long lastSortKey,
        UUID lastId,
        int limit) {

    public FeedCardQuery {
        scopes = Set.copyOf(scopes);
        participatedTournamentIds = Set.copyOf(participatedTournamentIds);
        excludedEntryIds = Set.copyOf(excludedEntryIds);
        if (limit < 1) {
            throw new IllegalArgumentException("limit >= 1");
        }
    }
}
```

`src/main/java/com/plantarena/feed/domain/FeedCardRepository.java`:

```java
package com.plantarena.feed.domain;

import java.util.List;
import java.util.UUID;

/**
 * Порт репозитория проекции карточек (ADR-002): запись — событиями
 * tournaments, чтение — keyset-страницами с псевдослучайным порядком.
 * Контракт фиксирует свойства порядка (детерминизм от seed, отсутствие
 * пропусков/дублей при продолжении курсора), а не значения хэша:
 * JPA-адаптер использует hashtextextended PostgreSQL, фейк — свой PRF.
 */
public interface FeedCardRepository {

    void saveAll(List<FeedCard> cards);

    void deleteAllByWindowId(UUID windowId);

    /** Страница длиной ≤ limit, отсортированная по (sortKey DESC, id DESC). */
    List<FeedCard> page(FeedCardQuery query);
}
```

`src/main/java/com/plantarena/feed/domain/FeedCursor.java`:

```java
package com.plantarena.feed.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Курсор ленты (раздел 9): seed, snapshotCutoff (новые карточки после него
 * исключаются), keyset-позиция (lastSortKey/lastId; null на первой странице)
 * и субъект — курсор не даёт прав на чужую ленту. Подписывается HMAC
 * (FeedCursorCodec, application).
 */
public record FeedCursor(long seed, Instant snapshotCutoff, Long lastSortKey, UUID lastId,
                         String subjectKey) {

    public FeedCursor {
        Objects.requireNonNull(snapshotCutoff, "snapshotCutoff");
        if (subjectKey == null || subjectKey.isBlank()) {
            throw new IllegalArgumentException("subjectKey обязательный");
        }
    }
}
```

`src/main/java/com/plantarena/feed/domain/FeedOrdering.java`:

```java
package com.plantarena.feed.domain;

import java.security.SecureRandom;

/**
 * Псевдослучайный порядок ленты (раздел 9): server-issued seed + стабильный
 * ID карточки. Sort key вычисляется на стороне хранилища (hashtextextended
 * в PostgreSQL) — таблица не грузится в память; стоимость сортировки
 * O(k log k) по отфильтрованным строкам на страницу, индекса по выражению
 * с параметром нет (документировано, ADR-002).
 */
public class FeedOrdering {

    private final SecureRandom random = new SecureRandom();

    /** Новый seed ленты (выдаётся при первом запросе, переносится курсором). */
    public long newSeed() {
        return random.nextLong();
    }
}
```

- [ ] **Step 2: Миграция**

`src/main/resources/db/migration/feed/V2__feed_cards.sql`:

```sql
-- Контекст feed: проекция карточек ленты (ADR-002, разделы 9, 11).
-- Обновляется опубликованными событиями tournaments (VotingWindowOpened/
-- Closed), обогащается данными plants/identity на момент открытия окна.
-- created_at — граница snapshotCutoff курсора: новые участники появляются
-- после обновления ленты. title/display_name — снимок (VARCHAR как у
-- владельцев данных: plants.plant.title, identity.app_user.display_name).
-- Без FK на схемы владельцев: контексты выделяются в сервисы (раздел 10.4).
CREATE TABLE feed_card (
    id                 UUID         PRIMARY KEY,
    window_id          UUID         NOT NULL,
    tournament_id      UUID         NOT NULL,
    scope              VARCHAR(13)  NOT NULL CHECK (scope IN ('PRIVATE', 'QUALIFICATION', 'FINAL')),
    cluster_id         UUID,
    entry_id           UUID         NOT NULL,
    user_id            UUID         NOT NULL,
    plant_id           UUID         NOT NULL,
    asset_id           UUID         NOT NULL,
    title              VARCHAR(100) NOT NULL,
    owner_display_name VARCHAR(100) NOT NULL,
    joined_at          TIMESTAMPTZ  NOT NULL,
    closes_at          TIMESTAMPTZ  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT feed_card_pair_uidx UNIQUE (window_id, entry_id)
);
CREATE INDEX feed_card_closes_idx ON feed_card (closes_at);
CREATE INDEX feed_card_created_idx ON feed_card (created_at);
```

- [ ] **Step 3: Красный контрактный тест**

`src/test/java/com/plantarena/feed/FeedCardRepositoryContractTest.java`:

```java
package com.plantarena.feed;

import com.plantarena.feed.domain.FeedCard;
import com.plantarena.feed.domain.FeedCardQuery;
import com.plantarena.feed.domain.FeedCardRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт репозитория проекции карточек: фейк и JPA-адаптер ведут себя
 * одинаково по свойствам (детерминизм от seed, keyset без пропусков/дублей,
 * фильтры), но не по значениям хэша (hashtextextended PG ≠ PRF фейка).
 */
@Transactional
@DisplayName("Контракт FeedCardRepository: проекция, keyset, фильтры")
public abstract class FeedCardRepositoryContractTest {

    private static final Instant T0 = Instant.parse("2026-09-28T10:00:00Z");
    private static final Instant LATER = T0.plusSeconds(10);
    private static final Set<String> ALL_SCOPES = Set.of("PRIVATE", "QUALIFICATION", "FINAL");
    private static final Set<String> GLOBAL_SCOPES = Set.of("QUALIFICATION", "FINAL");

    protected abstract FeedCardRepository repository();

    @Test
    @DisplayName("порядок детерминирован seed: одинаковый seed — одинаковый порядок всех карточек")
    void детерминизм_порядка() {
        repository().saveAll(List.of(
            card("a", T0), card("b", T0), card("c", T0), card("d", T0), card("e", T0)));

        List<FeedCard> first = repository().page(query(42L, 10));
        List<FeedCard> second = repository().page(query(42L, 10));

        assertThat(first).hasSize(5);
        assertThat(ids(first)).isEqualTo(ids(second));
        assertThat(first).allSatisfy(c -> assertThat(c.sortKey()).isNotEqualTo(0L));
    }

    @Test
    @DisplayName("keyset-продолжение: страницы не пересекаются и не пропускают карточки")
    void keyset_без_пропусков_и_дублей() {
        repository().saveAll(List.of(
            card("a", T0), card("b", T0), card("c", T0), card("d", T0), card("e", T0)));

        List<FeedCard> page1 = repository().page(query(42L, 2));
        FeedCard last1 = page1.get(1);
        List<FeedCard> page2 = repository().page(new FeedCardQuery(42L, T0.plusSeconds(1),
            T0.plusSeconds(1), ALL_SCOPES, Set.of(), null, Set.of(),
            last1.sortKey(), last1.id(), 2));
        FeedCard last2 = page2.get(1);
        List<FeedCard> page3 = repository().page(new FeedCardQuery(42L, T0.plusSeconds(1),
            T0.plusSeconds(1), ALL_SCOPES, Set.of(), null, Set.of(),
            last2.sortKey(), last2.id(), 2));

        Set<UUID> union = Set.copyOf(ids(page1));
        union.addAll(ids(page2));
        union.addAll(ids(page3));
        assertThat(union).hasSize(5);
        assertThat(page3).hasSize(1); // 2 + 2 + 1
    }

    @Test
    @DisplayName("фильтры: cutoff, открытое окно, scope, закрытые турниры, владелец, оцененные")
    void фильтры() {
        UUID privateTournament = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID votedEntry = UUID.fromString("00000000-0000-0000-0000-000000000001");
        repository().saveAll(List.of(
            card("old", T0),                                   // видима
            card("new", LATER),                                // после cutoff — скрыта
            closedCard("closed", T0),                          // окно закрыто — скрыта
            privateCard("priv", T0, privateTournament),        // PRIVATE без участия — скрыта
            ownCard("own", T0, owner),                         // своя — скрыта
            votedCard("voted", T0, votedEntry)));              // оцененная — скрыта

        FeedCardQuery query = new FeedCardQuery(42L, T0.plusSeconds(1), T0.plusSeconds(1),
            ALL_SCOPES, Set.of(), owner, Set.of(votedEntry), null, null, 10);
        List<FeedCard> page = repository().page(query);

        assertThat(ids(page)).containsExactly(idOf("old"));
    }

    @Test
    @DisplayName("гость: только глобальные scope, PRIVATE скрыты даже с участием")
    void гость_только_глобальные() {
        UUID privateTournament = UUID.randomUUID();
        repository().saveAll(List.of(
            card("global", T0),
            privateCard("priv", T0, privateTournament)));

        List<FeedCard> page = repository().page(new FeedCardQuery(42L, T0.plusSeconds(1),
            T0.plusSeconds(1), GLOBAL_SCOPES, Set.of(), null, Set.of(), null, null, 10));

        assertThat(ids(page)).containsExactly(idOf("global"));
    }

    // ---------- helpers: стабильные id из суффиксов ----------

    private UUID idOf(String suffix) {
        return UUID.nameUUIDFromBytes(("feed-card-" + suffix).getBytes());
    }

    private FeedCard card(String suffix, Instant createdAt) {
        return base(suffix, createdAt, "QUALIFICATION", UUID.randomUUID(), null, null);
    }

    private FeedCard closedCard(String suffix, Instant createdAt) {
        return base(suffix, createdAt, "QUALIFICATION", UUID.randomUUID(),
            T0.minusSeconds(1), null); // closes_at <= now
    }

    private FeedCard privateCard(String suffix, Instant createdAt, UUID tournamentId) {
        return base(suffix, createdAt, "PRIVATE", tournamentId, null, null);
    }

    private FeedCard ownCard(String suffix, Instant createdAt, UUID owner) {
        return base(suffix, createdAt, "QUALIFICATION", UUID.randomUUID(), null, owner);
    }

    private FeedCard votedCard(String suffix, Instant createdAt, UUID entryId) {
        FeedCard c = base(suffix, createdAt, "QUALIFICATION", UUID.randomUUID(), null, null);
        return new FeedCard(c.id(), c.windowId(), c.tournamentId(), c.scope(), c.clusterId(),
            entryId, c.userId(), c.plantId(), c.assetId(), c.title(), c.ownerDisplayName(),
            c.joinedAt(), c.closesAt(), c.createdAt(), 0L);
    }

    private FeedCard base(String suffix, Instant createdAt, String scope, UUID tournamentId,
                          Instant closesAt, UUID forcedOwner) {
        UUID id = idOf(suffix);
        return new FeedCard(id, UUID.nameUUIDFromBytes(("w-" + suffix).getBytes()), tournamentId,
            scope, null, id, forcedOwner != null ? forcedOwner : UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), "Фикус " + suffix, "Владелец " + suffix,
            T0, closesAt != null ? closesAt : T0.plusSeconds(3600), createdAt, 0L);
    }

    private FeedCardQuery query(long seed, int limit) {
        return new FeedCardQuery(seed, T0.plusSeconds(1), T0.plusSeconds(1), ALL_SCOPES,
            Set.of(), null, Set.of(), null, null, limit);
    }

    private List<UUID> ids(List<FeedCard> cards) {
        return cards.stream().map(FeedCard::id).toList();
    }
}
```

`src/test/java/com/plantarena/feed/domain/FeedCursorTest.java`:

```java
package com.plantarena.feed.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Курсор ленты (раздел 9): subjectKey обязателен, позиция keyset опциональна. */
@DisplayName("FeedCursor: валидация value object")
class FeedCursorTest {

    @Test
    @DisplayName("первая страница: lastSortKey/lastId отсутствуют; продолжение — заполнены")
    void валидный_курсор() {
        Instant cutoff = Instant.parse("2026-09-28T10:00:00Z");
        FeedCursor first = new FeedCursor(42L, cutoff, null, null, "USER:" + UUID.randomUUID());
        assertThat(first.seed()).isEqualTo(42L);
        FeedCursor next = new FeedCursor(42L, cutoff, -123L, UUID.randomUUID(),
            "GUEST:" + UUID.randomUUID());
        assertThat(next.lastSortKey()).isEqualTo(-123L);
    }

    @Test
    @DisplayName("без subjectKey курсор невозможен (не даёт прав на чужую ленту)")
    void без_субъекта() {
        assertThatThrownBy(() -> new FeedCursor(1L, Instant.now(), null, null, " "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeedCursor(1L, null, null, null, "USER:x"))
            .isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 4: Запустить — красный**

```bash
./mvnw -q test -Dtest='FeedCardRepositoryContractTest*,FeedCursorTest' -DfailIfNoTests=false
```

Ожидание: `FeedCursorTest` FAIL (класса нет — компиляция); контракт требует наследников (создаются в Step 5).

- [ ] **Step 5: JPA-реализация и фейк**

`src/main/java/com/plantarena/feed/adapter/out/persistence/FeedCardJpaEntity.java`:

```java
package com.plantarena.feed.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA-модель карточки ленты (ADR-002); проекция — без version. */
@Entity
@Table(name = "feed_card", schema = "feed")
public class FeedCardJpaEntity {

    @Id
    private UUID id;

    @Column(name = "window_id", nullable = false)
    private UUID windowId;

    @Column(name = "tournament_id", nullable = false)
    private UUID tournamentId;

    @Column(nullable = false)
    private String scope;

    @Column(name = "cluster_id")
    private UUID clusterId;

    @Column(name = "entry_id", nullable = false)
    private UUID entryId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "plant_id", nullable = false)
    private UUID plantId;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(nullable = false)
    private String title;

    @Column(name = "owner_display_name", nullable = false)
    private String ownerDisplayName;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "closes_at", nullable = false)
    private Instant closesAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    UUID getId() {
        return id;
    }

    UUID getWindowId() {
        return windowId;
    }

    UUID getTournamentId() {
        return tournamentId;
    }

    String getScope() {
        return scope;
    }

    UUID getClusterId() {
        return clusterId;
    }

    UUID getEntryId() {
        return entryId;
    }

    UUID getUserId() {
        return userId;
    }

    UUID getPlantId() {
        return plantId;
    }

    UUID getAssetId() {
        return assetId;
    }

    String getTitle() {
        return title;
    }

    String getOwnerDisplayName() {
        return ownerDisplayName;
    }

    Instant getJoinedAt() {
        return joinedAt;
    }

    Instant getClosesAt() {
        return closesAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setId(UUID id) {
        this.id = id;
    }

    void setWindowId(UUID windowId) {
        this.windowId = windowId;
    }

    void setTournamentId(UUID tournamentId) {
        this.tournamentId = tournamentId;
    }

    void setScope(String scope) {
        this.scope = scope;
    }

    void setClusterId(UUID clusterId) {
        this.clusterId = clusterId;
    }

    void setEntryId(UUID entryId) {
        this.entryId = entryId;
    }

    void setUserId(UUID userId) {
        this.userId = userId;
    }

    void setPlantId(UUID plantId) {
        this.plantId = plantId;
    }

    void setAssetId(UUID assetId) {
        this.assetId = assetId;
    }

    void setTitle(String title) {
        this.title = title;
    }

    void setOwnerDisplayName(String ownerDisplayName) {
        this.ownerDisplayName = ownerDisplayName;
    }

    void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }

    void setClosesAt(Instant closesAt) {
        this.closesAt = closesAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
```

`src/main/java/com/plantarena/feed/adapter/out/persistence/FeedCardJpaRepository.java`:

```java
package com.plantarena.feed.adapter.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data репозиторий проекции. Псевдослучайный sort key вычисляется в
 * SQL: hashtextextended(id::text, :seed) — детерминированная функция
 * PostgreSQL (раздел 9, ADR-002); сортировка по (sort_key DESC, id DESC).
 * Коллекции-параметры всегда непустые (адаптер подставляет sentinel).
 */
interface FeedCardJpaRepository extends JpaRepository<FeedCardJpaEntity, UUID> {

    @Query(value = """
        SELECT id, window_id, tournament_id, scope, cluster_id, entry_id, user_id, plant_id,
               asset_id, title, owner_display_name, joined_at, closes_at, created_at,
               hashtextextended(id::text, :seed) AS sort_key
        FROM feed.feed_card
        WHERE created_at <= :cutoff
          AND closes_at > :now
          AND scope IN (:scopes)
          AND (scope <> 'PRIVATE' OR tournament_id IN (:participated))
          AND user_id <> :excludedOwner
          AND entry_id NOT IN (:excludedEntries)
        ORDER BY sort_key DESC, id DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> firstPage(@Param("seed") long seed,
                             @Param("cutoff") Instant cutoff,
                             @Param("now") Instant now,
                             @Param("scopes") Collection<String> scopes,
                             @Param("participated") Collection<UUID> participated,
                             @Param("excludedOwner") UUID excludedOwner,
                             @Param("excludedEntries") Collection<UUID> excludedEntries,
                             @Param("limit") int limit);

    @Query(value = """
        SELECT id, window_id, tournament_id, scope, cluster_id, entry_id, user_id, plant_id,
               asset_id, title, owner_display_name, joined_at, closes_at, created_at,
               hashtextextended(id::text, :seed) AS sort_key
        FROM feed.feed_card
        WHERE created_at <= :cutoff
          AND closes_at > :now
          AND scope IN (:scopes)
          AND (scope <> 'PRIVATE' OR tournament_id IN (:participated))
          AND user_id <> :excludedOwner
          AND entry_id NOT IN (:excludedEntries)
          AND (hashtextextended(id::text, :seed) < :lastSortKey
               OR (hashtextextended(id::text, :seed) = :lastSortKey AND id < :lastId))
        ORDER BY sort_key DESC, id DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> nextPage(@Param("seed") long seed,
                            @Param("cutoff") Instant cutoff,
                            @Param("now") Instant now,
                            @Param("scopes") Collection<String> scopes,
                            @Param("participated") Collection<UUID> participated,
                            @Param("excludedOwner") UUID excludedOwner,
                            @Param("excludedEntries") Collection<UUID> excludedEntries,
                            @Param("lastSortKey") long lastSortKey,
                            @Param("lastId") UUID lastId,
                            @Param("limit") int limit);

    @Modifying
    @Query("delete from FeedCardJpaEntity c where c.windowId = :windowId")
    int deleteAllByWindowId(@Param("windowId") UUID windowId);
}
```

`src/main/java/com/plantarena/feed/adapter/out/persistence/JpaFeedCardRepository.java`:

```java
package com.plantarena.feed.adapter.out.persistence;

import com.plantarena.feed.domain.FeedCard;
import com.plantarena.feed.domain.FeedCardQuery;
import com.plantarena.feed.domain.FeedCardRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-реализация порта FeedCardRepository. Пустые множества фильтров
 * заменяются невозможным sentinel-UUID: семантика «ничего не совпало» без
 * пустого IN (...) в PostgreSQL. excludedOwner для гостя — тоже sentinel.
 */
@Repository
public class JpaFeedCardRepository implements FeedCardRepository {

    /** Не существует и не может существовать (RFC 9562 reserved). */
    private static final UUID SENTINEL = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final FeedCardJpaRepository jpaRepository;

    public JpaFeedCardRepository(FeedCardJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public void saveAll(List<FeedCard> cards) {
        for (FeedCard card : cards) {
            FeedCardJpaEntity entity = new FeedCardJpaEntity();
            entity.setId(card.id());
            entity.setWindowId(card.windowId());
            entity.setTournamentId(card.tournamentId());
            entity.setScope(card.scope());
            entity.setClusterId(card.clusterId());
            entity.setEntryId(card.entryId());
            entity.setUserId(card.userId());
            entity.setPlantId(card.plantId());
            entity.setAssetId(card.assetId());
            entity.setTitle(card.title());
            entity.setOwnerDisplayName(card.ownerDisplayName());
            entity.setJoinedAt(card.joinedAt());
            entity.setClosesAt(card.closesAt());
            entity.setCreatedAt(card.createdAt());
            jpaRepository.save(entity);
        }
    }

    @Override
    @Transactional
    public void deleteAllByWindowId(UUID windowId) {
        jpaRepository.deleteAllByWindowId(windowId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeedCard> page(FeedCardQuery query) {
        CollectionLike params = paramsOf(query);
        List<Object[]> rows = query.lastSortKey() == null
            ? jpaRepository.firstPage(query.seed(), query.snapshotCutoff(), query.now(),
                params.scopes, params.participated, params.excludedOwner, params.excludedEntries,
                query.limit())
            : jpaRepository.nextPage(query.seed(), query.snapshotCutoff(), query.now(),
                params.scopes, params.participated, params.excludedOwner, params.excludedEntries,
                query.lastSortKey(), query.lastId(), query.limit());
        List<FeedCard> cards = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            cards.add(new FeedCard((UUID) row[0], (UUID) row[1], (UUID) row[2], (String) row[3],
                (UUID) row[4], (UUID) row[5], (UUID) row[6], (UUID) row[7], (UUID) row[8],
                (String) row[9], (String) row[10], (Instant) row[11], (Instant) row[12],
                (Instant) row[13], ((Number) row[14]).longValue()));
        }
        return cards;
    }

    private CollectionLike paramsOf(FeedCardQuery query) {
        Set<UUID> participated = query.participatedTournamentIds().isEmpty()
            ? Set.of(SENTINEL) : query.participatedTournamentIds();
        Set<UUID> excludedEntries = query.excludedEntryIds().isEmpty()
            ? Set.of(SENTINEL) : query.excludedEntryIds();
        UUID excludedOwner = query.excludedOwnerUserId() != null
            ? query.excludedOwnerUserId() : SENTINEL;
        return new CollectionLike(query.scopes(), participated, excludedOwner, excludedEntries);
    }

    private record CollectionLike(Set<String> scopes, Set<UUID> participated,
                                   UUID excludedOwner, Set<UUID> excludedEntries) {
    }
}
```

`src/test/java/com/plantarena/feed/application/support/InMemoryFeedCardRepository.java`:

```java
package com.plantarena.feed.application.support;

import com.plantarena.feed.domain.FeedCard;
import com.plantarena.feed.domain.FeedCardQuery;
import com.plantarena.feed.domain.FeedCardRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Фейк репозитория карточек для application-тестов. PRF фейка ≠
 * hashtextextended PostgreSQL — контракт фиксирует свойства порядка
 * (детерминизм от seed, keyset без пропусков/дублей), а не значения хэша.
 */
public class InMemoryFeedCardRepository implements FeedCardRepository {

    private final Map<UUID, FeedCard> cards = new ConcurrentHashMap<>();

    /** Детерминированный PRF фейка: (id, seed) → long. */
    static long sortKey(UUID id, long seed) {
        return (id.getMostSignificantBits() ^ id.getLeastSignificantBits())
            * 0x9E3779B97F4A7C15L + seed;
    }

    @Override
    public void saveAll(List<FeedCard> newCards) {
        for (FeedCard card : newCards) {
            cards.put(card.id(), card);
        }
    }

    @Override
    public void deleteAllByWindowId(UUID windowId) {
        cards.values().removeIf(card -> card.windowId().equals(windowId));
    }

    @Override
    public List<FeedCard> page(FeedCardQuery query) {
        Comparator<FeedCard> order = Comparator
            .comparingLong((FeedCard c) -> sortKey(c.id(), query.seed())).reversed()
            .thenComparing(FeedCard::id, Comparator.reverseOrder());
        return cards.values().stream()
            .filter(c -> !c.createdAt().isAfter(query.snapshotCutoff()))
            .filter(c -> c.closesAt().isAfter(query.now()))
            .filter(c -> query.scopes().contains(c.scope()))
            .filter(c -> !"PRIVATE".equals(c.scope())
                || query.participatedTournamentIds().contains(c.tournamentId()))
            .filter(c -> query.excludedOwnerUserId() == null
                || !c.userId().equals(query.excludedOwnerUserId()))
            .filter(c -> !query.excludedEntryIds().contains(c.entryId()))
            .filter(c -> query.lastSortKey() == null
                || sortKey(c.id(), query.seed()) < query.lastSortKey()
                || (sortKey(c.id(), query.seed()) == query.lastSortKey()
                    && c.id().compareTo(query.lastId()) < 0))
            .sorted(order)
            .limit(query.limit())
            .map(c -> new FeedCard(c.id(), c.windowId(), c.tournamentId(), c.scope(),
                c.clusterId(), c.entryId(), c.userId(), c.plantId(), c.assetId(), c.title(),
                c.ownerDisplayName(), c.joinedAt(), c.closesAt(), c.createdAt(),
                sortKey(c.id(), query.seed())))
            .collect(Collectors.toList());
    }
}
```

`src/test/java/com/plantarena/feed/application/support/InMemoryFeedCardRepositoryContractTest.java`:

```java
package com.plantarena.feed.application.support;

import com.plantarena.feed.FeedCardRepositoryContractTest;
import com.plantarena.feed.domain.FeedCardRepository;

/** In-memory-наследник контракта FeedCardRepository. */
class InMemoryFeedCardRepositoryContractTest extends FeedCardRepositoryContractTest {

    private final InMemoryFeedCardRepository repository = new InMemoryFeedCardRepository();

    @Override
    protected FeedCardRepository repository() {
        return repository;
    }
}
```

`src/test/java/com/plantarena/feed/adapter/out/persistence/JpaFeedCardRepositoryContractIT.java`:

```java
package com.plantarena.feed.adapter.out.persistence;

import com.plantarena.feed.FeedCardRepositoryContractTest;
import com.plantarena.feed.domain.FeedCardRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/** JPA-наследник контракта FeedCardRepository на Testcontainers (hashtextextended). */
@DataJpaTest
@Import(JpaFeedCardRepository.class)
class JpaFeedCardRepositoryContractIT extends FeedCardRepositoryContractTest {

    @Autowired
    private JpaFeedCardRepository repository;

    @Override
    protected FeedCardRepository repository() {
        return repository;
    }
}
```

Примечание: если `@DataJpaTest` в проекте не поднимает Flyway-схему `feed` — следовать паттерну существующих JPA-контрактных IT контекста tournaments (`JpaQualificationEpochRepositoryContractIT`).

- [ ] **Step 6: Запустить — зелёный, затем весь verify**

```bash
./mvnw -q test -Dtest='FeedCardRepositoryContractTest*,FeedCursorTest' -DfailIfNoTests=false
./mvnw -q verify
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/plantarena/feed/domain/ \
  src/main/resources/db/migration/feed/V2__feed_cards.sql \
  src/main/java/com/plantarena/feed/adapter/out/persistence/ \
  src/test/java/com/plantarena/feed/FeedCardRepositoryContractTest.java \
  src/test/java/com/plantarena/feed/domain/FeedCursorTest.java \
  src/test/java/com/plantarena/feed/application/support/InMemoryFeedCardRepository.java \
  src/test/java/com/plantarena/feed/application/support/InMemoryFeedCardRepositoryContractTest.java \
  src/test/java/com/plantarena/feed/adapter/out/persistence/JpaFeedCardRepositoryContractIT.java
git commit -m "feat(feed): домен read-модели (FeedCard/FeedCursor/FeedOrdering), миграция V2, JPA с hashtextextended и контракт"
```

---

### Task 7: tournaments — read-контракты для feed (`FeedDirectory`, `GuestSessionDirectory`)

**Files:**
- Create: `src/main/java/com/plantarena/tournaments/api/FeedDirectory.java`
- Create: `src/main/java/com/plantarena/tournaments/api/GuestSessionDirectory.java`
- Create: `src/main/java/com/plantarena/tournaments/application/FeedDirectoryFacade.java`
- Create: `src/main/java/com/plantarena/tournaments/application/GuestSessionDirectoryFacade.java`
- Create: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/VoteJpaRepository.java`
- Modify: `src/main/java/com/plantarena/tournaments/domain/VotingWindowRepository.java` (+1 метод)
- Modify: `src/main/java/com/plantarena/tournaments/domain/TournamentEntryRepository.java` (+1 метод)
- Modify: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaVotingWindowRepository.java`
- Modify: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/TournamentEntryJpaRepository.java`
- Modify: `src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaTournamentEntryRepository.java`
- Modify: `src/test/java/com/plantarena/tournaments/application/support/InMemoryVotingWindowRepository.java`
- Modify: `src/test/java/com/plantarena/tournaments/application/support/InMemoryTournamentEntryRepository.java`
- Test: `src/test/java/com/plantarena/tournaments/application/FeedDirectoryFacadeTest.java`
- Test: `src/test/java/com/plantarena/tournaments/application/GuestSessionDirectoryFacadeTest.java`
- Test: `src/test/java/com/plantarena/tournaments/VotingWindowRepositoryContractTest.java` (дополнение)
- Test: `src/test/java/com/plantarena/tournaments/TournamentEntryRepositoryContractTest.java` (дополнение)

**Interfaces:**
- Consumes: `VotingWindowRepository`, `TournamentEntryRepository`, `GuestSessionRepository` (Task 3), `GuestTokens` (Task 4).
- Produces (для Task 8): `tournaments.api.FeedDirectory { Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey); Set<UUID> findParticipatedTournamentIds(UUID userId); }`; `tournaments.api.GuestSessionDirectory { Optional<UUID> activeSessionId(String rawToken); }`; `VotingWindowRepository.findVotedEntryIdsInOpenWindows(String subjectKey)`; `TournamentEntryRepository.findTournamentIdsByUserId(UUID userId)`.

- [ ] **Step 1: Порты доменных репозиториев (+методы)**

В `VotingWindowRepository.java` добавить:

```java
    /** Entry, оценённые субъектом в открытых окнах (лента, раздел 9). */
    Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey);
```

(импорт `java.util.Set`).

В `TournamentEntryRepository.java` добавить:

```java
    /** Турниры, где пользователь был допущен к старту (лента, раздел 9). */
    Set<UUID> findTournamentIdsByUserId(UUID userId);
```

(импорт `java.util.Set`).

- [ ] **Step 2: Контрактные дополнения (красные)**

В `VotingWindowRepositoryContractTest` добавить тест (фикстуры класса: `repository()`, `newTournamentId()`, `OPENS`/`CLOSES`):

```java
    @Test
    @DisplayName("findVotedEntryIdsInOpenWindows: только голоса субъекта в открытых окнах")
    void голоса_субъекта_в_открытых_окнах() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        UUID entry1 = UUID.randomUUID();
        UUID entry2 = UUID.randomUUID();
        UUID entry3 = UUID.randomUUID();
        UUID tournamentId = newTournamentId();
        VotingWindow open = VotingWindow.open(tournamentId, 1, List.of(
            new VotingWindow.ParticipantSeed(entry1, user1, OPENS),
            new VotingWindow.ParticipantSeed(entry2, user2, OPENS)),
            OPENS, CLOSES, OPENS);
        open.castVote(VotingSubject.user(user1), entry2, VoteValue.LIKE, OPENS);
        repository().save(open);
        VotingWindow closed = VotingWindow.open(tournamentId, 2, List.of(
            new VotingWindow.ParticipantSeed(entry1, user1, OPENS),
            new VotingWindow.ParticipantSeed(entry3, user2, OPENS)),
            OPENS, CLOSES, OPENS);
        closed.castVote(VotingSubject.user(user1), entry3, VoteValue.DISLIKE, OPENS);
        closed.close(CLOSES, new RoundElimination(), 0.5);
        repository().save(closed);

        assertThat(repository().findVotedEntryIdsInOpenWindows(
                VotingSubject.user(user1).subjectKey()))
            .containsExactly(entry2); // голос в закрытом окне не считается
    }
```

В `TournamentEntryRepositoryContractTest` добавить тест (по фиксурам класса: `repository()`, `newTournamentId()`, способ создания `TournamentEntry.admit(...)` из существующих тестов):

```java
    @Test
    @DisplayName("findTournamentIdsByUserId: турниры участника, включая выбывшего (допущение 9)")
    void турниры_участия_пользователя() {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID t1 = newTournamentId();
        UUID t2 = newTournamentId();
        UUID t3 = newTournamentId();
        TournamentEntry active = repository().save(TournamentEntry.admit(t1, user,
            UUID.randomUUID(), UUID.randomUUID(), OPENS));
        TournamentEntry eliminated = repository().save(TournamentEntry.admit(t2, user,
            UUID.randomUUID(), UUID.randomUUID(), OPENS));
        eliminated.eliminate();
        repository().save(eliminated);
        repository().save(TournamentEntry.admit(t3, other, UUID.randomUUID(),
            UUID.randomUUID(), OPENS));

        assertThat(repository().findTournamentIdsByUserId(user))
            .containsExactlyInAnyOrder(t1, t2);
    }
```

(`OPENS` — существующая константа класса; иначе `Instant.parse(...)`.)

- [ ] **Step 3: JPA-реализации**

`src/main/java/com/plantarena/tournaments/adapter/out/persistence/VoteJpaRepository.java` (новый Spring Data репозиторий над существующим `VoteJpaEntity`):

```java
package com.plantarena.tournaments.adapter.out.persistence;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Запросы к голосам (раздел 9): оцененные субъектом entry в открытых окнах. */
interface VoteJpaRepository extends JpaRepository<VoteJpaEntity, UUID> {

    @Query(value = """
        select wp.entry_id
        from tournaments.vote v
        join tournaments.window_participant wp on wp.id = v.window_participant_id
        join tournaments.voting_window w on w.id = wp.window_id
        where v.subject_key = :subjectKey and w.status = 'OPEN'
        """, nativeQuery = true)
    Set<UUID> findVotedEntryIdsInOpenWindows(@Param("subjectKey") String subjectKey);
}
```

В `JpaVotingWindowRepository`: добавить зависимость `VoteJpaRepository` в конструктор и метод:

```java
    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey) {
        return voteJpaRepository.findVotedEntryIdsInOpenWindows(subjectKey);
    }
```

В `TournamentEntryJpaRepository` добавить:

```java
    @Query("select distinct e.tournamentId from TournamentEntryJpaEntity e where e.userId = :userId")
    Set<UUID> findTournamentIdsByUserId(@Param("userId") UUID userId);
```

(импорты `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`, `java.util.Set`).

В `JpaTournamentEntryRepository` добавить:

```java
    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findTournamentIdsByUserId(UUID userId) {
        return jpaRepository.findTournamentIdsByUserId(userId);
    }
```

В `InMemoryVotingWindowRepository` (test) добавить:

```java
    @Override
    public Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey) {
        return windows.values().stream()
            .filter(w -> w.status() == WindowStatus.OPEN)
            .flatMap(w -> w.participants().stream()
                .map(p -> w.myVote(subjectKey, p.entryId()))
                .filter(java.util.Objects::nonNull)
                .map(v -> w.participants().stream()
                    .filter(p -> w.myVote(subjectKey, p.entryId()) != null)
                    .map(WindowParticipant::entryId).toList()))
            .flatMap(List::stream)
            .collect(java.util.stream.Collectors.toSet());
    }
```

**Примечание:** реализация выше неэффективна/запутана — реализовать проще и прямо:

```java
    @Override
    public Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey) {
        Set<UUID> voted = new java.util.HashSet<>();
        for (VotingWindow window : windows.values()) {
            if (window.status() != WindowStatus.OPEN) {
                continue;
            }
            for (WindowParticipant participant : window.participants()) {
                if (window.myVote(subjectKey, participant.entryId()) != null) {
                    voted.add(participant.entryId());
                }
            }
        }
        return voted;
    }
```

(использовать второй вариант; `windows` — внутреннее хранилище фейка, `WindowStatus`/`WindowParticipant` — импорты домена).

В `InMemoryTournamentEntryRepository` (test) добавить (по внутреннему хранилищу фейка):

```java
    @Override
    public Set<UUID> findTournamentIdsByUserId(UUID userId) {
        return entries.values().stream()
            .filter(e -> e.userId().equals(userId))
            .map(TournamentEntry::tournamentId)
            .collect(java.util.stream.Collectors.toSet());
    }
```

(имена полей/геттеров — по фактической структуре фейка; `TournamentEntry.tournamentId()` существует).

- [ ] **Step 4: Публичные контракты и фасады**

`src/main/java/com/plantarena/tournaments/api/FeedDirectory.java`:

```java
package com.plantarena.tournaments.api;

import java.util.Set;
import java.util.UUID;

/**
 * Read-контракт tournaments для feed (раздел 9): только чтение, без
 * доменных типов. Реализация — фасад application (FeedDirectoryFacade).
 */
public interface FeedDirectory {

    /** Entry, уже оценённые субъектом в открытых окнах (карточки не предлагаются). */
    Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey);

    /** Турниры, где пользователь был допущен к старту (включая выбывших — допущение 9). */
    Set<UUID> findParticipatedTournamentIds(UUID userId);
}
```

`src/main/java/com/plantarena/tournaments/api/GuestSessionDirectory.java`:

```java
package com.plantarena.tournaments.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-контракт tournaments для feed (раздел 9): разрешение гостевого
 * токена ленты. Реализация — фасад application (GuestSessionDirectoryFacade).
 */
public interface GuestSessionDirectory {

    /** Активная сессия по сырому токену: проверяет хэш и срок действия. */
    Optional<UUID> activeSessionId(String rawToken);
}
```

`src/main/java/com/plantarena/tournaments/application/FeedDirectoryFacade.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.tournaments.api.FeedDirectory;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Фасад read-контракта feed (раздел 9): порты доменных репозиториев. */
@Service
public class FeedDirectoryFacade implements FeedDirectory {

    private final VotingWindowRepository windows;
    private final TournamentEntryRepository entries;

    public FeedDirectoryFacade(VotingWindowRepository windows,
                               TournamentEntryRepository entries) {
        this.windows = windows;
        this.entries = entries;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey) {
        return windows.findVotedEntryIdsInOpenWindows(subjectKey);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findParticipatedTournamentIds(UUID userId) {
        return entries.findTournamentIdsByUserId(userId);
    }
}
```

`src/main/java/com/plantarena/tournaments/application/GuestSessionDirectoryFacade.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.tournaments.api.GuestSessionDirectory;
import com.plantarena.tournaments.domain.GuestSessionRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Фасад read-контракта feed (раздел 9): хэш токена + срок действия. */
@Service
public class GuestSessionDirectoryFacade implements GuestSessionDirectory {

    private final GuestSessionRepository sessions;
    private final Clock clock;

    public GuestSessionDirectoryFacade(GuestSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> activeSessionId(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return sessions.findByTokenHash(GuestTokens.sha256Hex(rawToken.trim()))
            .filter(session -> session.isActive(clock.instant()))
            .map(session -> session.id());
    }
}
```

- [ ] **Step 5: Тесты фасадов**

`src/test/java/com/plantarena/tournaments/application/FeedDirectoryFacadeTest.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.application.support.InMemoryVotingWindowRepository;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingSubject;
import com.plantarena.tournaments.domain.VotingWindow;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Фасад read-контракта feed (раздел 9): делегирует портам репозиториев. */
@DisplayName("FeedDirectoryFacade: оцененные entry и турниры участия")
class FeedDirectoryFacadeTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryVotingWindowRepository windows = new InMemoryVotingWindowRepository();
    private final InMemoryTournamentEntryRepository entries =
        new InMemoryTournamentEntryRepository(tournaments);
    private final FeedDirectoryFacade facade =
        new FeedDirectoryFacade(windows, entries);

    @Test
    @DisplayName("оцененные субъектом entry — из открытых окон")
    void оцененные() {
        UUID user = UUID.randomUUID();
        UUID entry1 = UUID.randomUUID();
        UUID entry2 = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        VotingWindow window = VotingWindow.open(tournamentId, 1, List.of(
                new VotingWindow.ParticipantSeed(entry1, UUID.randomUUID(), NOW),
                new VotingWindow.ParticipantSeed(entry2, user, NOW)),
            NOW, NOW.plusSeconds(3600), NOW);
        window.castVote(VotingSubject.user(user), entry1, VoteValue.LIKE, NOW);
        windows.save(window);

        assertThat(facade.findVotedEntryIdsInOpenWindows(
                VotingSubject.user(user).subjectKey()))
            .containsExactly(entry1);
    }

    @Test
    @DisplayName("турниры участия: допущен к старту — включая выбывшего (допущение 9)")
    void турниры_участия() {
        UUID user = UUID.randomUUID();
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        entries.save(TournamentEntry.admit(t1, user, UUID.randomUUID(), UUID.randomUUID(), NOW));
        TournamentEntry eliminated = entries.save(TournamentEntry.admit(t2, user,
            UUID.randomUUID(), UUID.randomUUID(), NOW));
        eliminated.eliminate();
        entries.save(eliminated);

        assertThat(facade.findParticipatedTournamentIds(user)).containsExactlyInAnyOrder(t1, t2);
    }
}
```

`src/test/java/com/plantarena/tournaments/application/GuestSessionDirectoryFacadeTest.java`:

```java
package com.plantarena.tournaments.application;

import com.plantarena.tournaments.application.support.InMemoryGuestSessionRepository;
import com.plantarena.tournaments.domain.GuestSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Фасад read-контракта feed (раздел 9): активная сессия по сырому токену. */
@DisplayName("GuestSessionDirectoryFacade: токен → активная сессия")
class GuestSessionDirectoryFacadeTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private final InMemoryGuestSessionRepository sessions = new InMemoryGuestSessionRepository();
    private final GuestSessionDirectoryFacade facade = new GuestSessionDirectoryFacade(
        sessions, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("активная сессия: хэш совпал и срок не истёк")
    void активная_сессия() {
        String token = GuestTokens.newToken();
        UUID id = UUID.randomUUID();
        sessions.save(GuestSession.issue(id, GuestTokens.sha256Hex(token), NOW,
            Duration.ofHours(1)));

        assertThat(facade.activeSessionId(token)).contains(id);
    }

    @Test
    @DisplayName("истёкшая/неизвестная/пустая — пусто")
    void неактивные() {
        String token = GuestTokens.newToken();
        sessions.save(GuestSession.issue(UUID.randomUUID(), GuestTokens.sha256Hex(token),
            NOW.minus(Duration.ofHours(2)), Duration.ofHours(1))); // истёкшая

        assertThat(facade.activeSessionId(token)).isEmpty();
        assertThat(facade.activeSessionId(GuestTokens.newToken())).isEmpty();
        assertThat(facade.activeSessionId(" ")).isEmpty();
        assertThat(facade.activeSessionId(null)).isEmpty();
    }
}
```

- [ ] **Step 6: Запустить — зелёный, затем весь verify**

```bash
./mvnw -q test -Dtest='FeedDirectoryFacadeTest,GuestSessionDirectoryFacadeTest,VotingWindowRepositoryContractTest*,TournamentEntryRepositoryContractTest*' -DfailIfNoTests=false
./mvnw -q verify
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/plantarena/tournaments/api/FeedDirectory.java \
  src/main/java/com/plantarena/tournaments/api/GuestSessionDirectory.java \
  src/main/java/com/plantarena/tournaments/application/FeedDirectoryFacade.java \
  src/main/java/com/plantarena/tournaments/application/GuestSessionDirectoryFacade.java \
  src/main/java/com/plantarena/tournaments/adapter/out/persistence/VoteJpaRepository.java \
  src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaVotingWindowRepository.java \
  src/main/java/com/plantarena/tournaments/adapter/out/persistence/TournamentEntryJpaRepository.java \
  src/main/java/com/plantarena/tournaments/adapter/out/persistence/JpaTournamentEntryRepository.java \
  src/main/java/com/plantarena/tournaments/domain/VotingWindowRepository.java \
  src/main/java/com/plantarena/tournaments/domain/TournamentEntryRepository.java \
  src/test/java/com/plantarena/tournaments/application/support/InMemoryVotingWindowRepository.java \
  src/test/java/com/plantarena/tournaments/application/support/InMemoryTournamentEntryRepository.java \
  src/test/java/com/plantarena/tournaments/application/FeedDirectoryFacadeTest.java \
  src/test/java/com/plantarena/tournaments/application/GuestSessionDirectoryFacadeTest.java \
  src/test/java/com/plantarena/tournaments/VotingWindowRepositoryContractTest.java \
  src/test/java/com/plantarena/tournaments/TournamentEntryRepositoryContractTest.java
git commit -m "feat(tournaments): read-контракты FeedDirectory/GuestSessionDirectory для ленты"
```

---

### Task 8: feed — application, проекция, ACL, wiring

**Files:**
- Create: `src/main/java/com/plantarena/feed/application/FeedSettings.java`
- Create: `src/main/java/com/plantarena/feed/application/FeedCursorCodec.java`
- Create: `src/main/java/com/plantarena/feed/application/FeedCursorInvalidException.java`
- Create: `src/main/java/com/plantarena/feed/application/FeedCursorExpiredException.java`
- Create: `src/main/java/com/plantarena/feed/application/FeedService.java`
- Create: `src/main/java/com/plantarena/feed/application/FeedProjectionService.java`
- Create: `src/main/java/com/plantarena/feed/application/port/in/GetFeedUseCase.java`
- Create: `src/main/java/com/plantarena/feed/application/port/in/ProjectWindowUseCase.java`
- Create: `src/main/java/com/plantarena/feed/adapter/in/events/VotingWindowOpenedHandler.java`
- Create: `src/main/java/com/plantarena/feed/adapter/in/events/VotingWindowClosedHandler.java`
- Create: `src/main/java/com/plantarena/feed/adapter/in/web/FeedExceptionHandler.java`
- Create: `src/main/java/com/plantarena/feed/adapter/out/tournaments/InProcessVotingDirectory.java`
- Create: `src/main/java/com/plantarena/feed/adapter/out/tournaments/InProcessGuestSessions.java`
- Create: `src/main/java/com/plantarena/feed/adapter/out/plants/InProcessPlantCatalog.java`
- Create: `src/main/java/com/plantarena/feed/adapter/out/identity/InProcessOwnerDirectory.java`
- Create: `src/main/java/com/plantarena/config/FeedWiringConfig.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/plantarena/feed/application/FeedCursorCodecTest.java`
- Test: `src/test/java/com/plantarena/feed/application/FeedServiceTest.java`
- Test: `src/test/java/com/plantarena/feed/application/FeedProjectionServiceTest.java`
- Test: `src/test/java/com/plantarena/feed/application/support/FakeVotingDirectory.java`
- Test: `src/test/java/com/plantarena/feed/application/support/FakeGuestSessions.java`

**Interfaces:**
- Consumes: порты Task 1, `FeedCardRepository`/`FeedCursor`/`FeedOrdering` (Task 6), события Task 5, `tournaments.api.{FeedDirectory, GuestSessionDirectory}` (Task 7), `plants.api.PlantDirectory`, `identity.api.UserDirectory`, `CurrentActor` (shared).
- Produces (для Task 10): `GetFeedUseCase { FeedPage get(CurrentActor actor, String guestToken, Integer limit, String cursor); record FeedPage(List<FeedItem> items, String nextCursor, boolean hasNext); record FeedItem(UUID windowId, String scope, UUID tournamentId, UUID entryId, UUID plantId, String title, String imageUrl, UUID ownerId, String ownerDisplayName, Instant closesAt) }`; `ProjectWindowUseCase { void onWindowOpened(WindowCardsCommand command); void onWindowClosed(UUID windowId); record WindowCardsCommand(UUID windowId, UUID tournamentId, String scope, UUID clusterId, Instant closesAt, List<CardSeed> cards); record CardSeed(UUID entryId, UUID userId, UUID plantId, Instant joinedAt) }`; `FeedCursorCodec { String encode(FeedCursor); FeedCursor decode(String encoded, Instant now); }`; `FeedExceptionHandler` → 400 `FEED_CURSOR_INVALID` / 410 `FEED_CURSOR_EXPIRED`.

- [ ] **Step 1: Красные тесты**

`src/test/java/com/plantarena/feed/application/FeedCursorCodecTest.java`:

```java
package com.plantarena.feed.application;

import com.plantarena.feed.domain.FeedCursor;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Курсор ленты (раздел 9): HMAC-SHA256, tamper → 400, истёкший → 410. */
@DisplayName("FeedCursorCodec: подпись, tamper, TTL")
class FeedCursorCodecTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private final FeedCursorCodec codec =
        new FeedCursorCodec("test-secret", Duration.ofHours(1));

    @Test
    @DisplayName("roundtrip: encode → decode восстанавливает курсор")
    void roundtrip() {
        FeedCursor cursor = new FeedCursor(42L, NOW, -7L, UUID.randomUUID(),
            "USER:" + UUID.randomUUID());

        FeedCursor decoded = codec.decode(codec.encode(cursor), NOW.plusSeconds(10));

        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    @DisplayName("подмена payload или подписи — FeedCursorInvalidException")
    void подмена() {
        FeedCursor cursor = new FeedCursor(42L, NOW, null, null, "USER:x");
        String encoded = codec.encode(cursor);

        String[] parts = encoded.split("\\.");
        String tamperedPayload = parts[0] + "x"; // изменили payload
        assertThatThrownBy(() -> codec.decode(tamperedPayload + "." + parts[1], NOW))
            .isInstanceOf(FeedCursorInvalidException.class);
        assertThatThrownBy(() -> codec.decode(parts[0] + "." + parts[1].replaceFirst(".", "x"),
            NOW)).isInstanceOf(FeedCursorInvalidException.class);
        assertThatThrownBy(() -> codec.decode("garbage", NOW))
            .isInstanceOf(FeedCursorInvalidException.class);
    }

    @Test
    @DisplayName("чужой секрет — невалидная подпись")
    void чужой_секрет() {
        FeedCursor cursor = new FeedCursor(1L, NOW, null, null, "GUEST:" + UUID.randomUUID());
        String encoded = new FeedCursorCodec("other-secret", Duration.ofHours(1))
            .encode(cursor);

        assertThatThrownBy(() -> codec.decode(encoded, NOW))
            .isInstanceOf(FeedCursorInvalidException.class);
    }

    @Test
    @DisplayName("истёкший курсор (snapshotCutoff + ttl < now) — FeedCursorExpiredException")
    void истёкший() {
        FeedCursor cursor = new FeedCursor(1L, NOW, null, null, "USER:x");
        String encoded = codec.encode(cursor);

        assertThatThrownBy(() -> codec.decode(encoded, NOW.plus(Duration.ofHours(2))))
            .isInstanceOf(FeedCursorExpiredException.class);
        // ровно на границе TTL — ещё валиден
        assertThat(codec.decode(encoded, NOW.plus(Duration.ofHours(1)))).isEqualTo(cursor);
    }
}
```

`src/test/java/com/plantarena/feed/application/support/FakeVotingDirectory.java`:

```java
package com.plantarena.feed.application.support;

import com.plantarena.feed.application.port.out.VotingDirectory;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Фейк read-порта tournaments для тестов FeedService. */
public class FakeVotingDirectory implements VotingDirectory {

    public final Set<UUID> votedEntryIds = new HashSet<>();
    public final Set<UUID> participatedTournamentIds = new HashSet<>();

    @Override
    public Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey) {
        return votedEntryIds;
    }

    @Override
    public Set<UUID> findParticipatedTournamentIds(UUID userId) {
        return participatedTournamentIds;
    }
}
```

`src/test/java/com/plantarena/feed/application/support/FakeGuestSessions.java`:

```java
package com.plantarena.feed.application.support;

import com.plantarena.feed.application.port.out.GuestSessions;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Фейк read-порта гостевых сессий для тестов FeedService. */
public class FakeGuestSessions implements GuestSessions {

    public final Map<String, UUID> activeByToken = new HashMap<>();

    @Override
    public Optional<UUID> activeSessionId(String rawToken) {
        return Optional.ofNullable(activeByToken.get(rawToken));
    }
}
```

`src/test/java/com/plantarena/feed/application/FeedServiceTest.java`:

```java
package com.plantarena.feed.application;

import com.plantarena.feed.application.port.in.GetFeedUseCase;
import com.plantarena.feed.application.port.out.GuestSessions;
import com.plantarena.feed.application.port.out.OwnerDirectory;
import com.plantarena.feed.application.port.out.PlantCatalog;
import com.plantarena.feed.application.port.out.VotingDirectory;
import com.plantarena.feed.application.support.FakeGuestSessions;
import com.plantarena.feed.application.support.FakeVotingDirectory;
import com.plantarena.feed.application.support.InMemoryFeedCardRepository;
import com.plantarena.feed.domain.FeedCard;
import com.plantarena.feed.domain.FeedCursor;
import com.plantarena.feed.domain.FeedOrdering;
import com.plantarena.shared.security.NotIdentifiedException;
import com.plantarena.shared.web.InvalidPaginationException;
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

/**
 * Лента (раздел 9): субъект и права, keyset-пагинация limit+1, курсор
 * привязан к субъекту, оцененные исключаются.
 */
@DisplayName("FeedService: субъект, права, keyset, курсор")
class FeedServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final Set<String> ALL_SCOPES = Set.of("PRIVATE", "QUALIFICATION", "FINAL");

    private final InMemoryFeedCardRepository cards = new InMemoryFeedCardRepository();
    private final FakeVotingDirectory voting = new FakeVotingDirectory();
    private final FakeGuestSessions guests = new FakeGuestSessions();
    private final FeedCursorCodec codec = new FeedCursorCodec("test-secret",
        Duration.ofHours(1));
    private final FeedService service = new FeedService(cards, voting, guests,
        new FeedOrdering(), codec, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID viewer = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @Test
    @DisplayName("пользователь: глобальные + закрытые своих турниров, чужие карточки, без total")
    void лента_пользователя() {
        UUID privateTournament = UUID.randomUUID();
        voting.participatedTournamentIds.add(privateTournament);
        cards.saveAll(List.of(
            card("g1", "QUALIFICATION", UUID.randomUUID(), UUID.randomUUID()),
            card("g2", "FINAL", UUID.randomUUID(), UUID.randomUUID()),
            card("p1", "PRIVATE", privateTournament, UUID.randomUUID()),
            card("p2", "PRIVATE", UUID.randomUUID(), UUID.randomUUID()), // чужой закрытый
            card("own", "QUALIFICATION", UUID.randomUUID(), viewer)));   // своя

        GetFeedUseCase.FeedPage page = service.get(
            com.plantarena.shared.security.CurrentActor.identified(viewer, Set.of()),
            null, 20, null);

        assertThat(page.items()).extracting(GetFeedUseCase.FeedItem::entryId)
            .containsExactlyInAnyOrder(idOf("g1"), idOf("g2"), idOf("p1"));
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.items()).allSatisfy(item -> {
            assertThat(item.imageUrl()).startsWith("/api/v1/files/");
            assertThat(item.ownerId()).isNotNull();
            assertThat(item.ownerDisplayName()).isNotBlank();
        });
    }

    @Test
    @DisplayName("гость: только глобальные; без токена и с неизвестным токеном — 401")
    void лента_гостя() {
        cards.saveAll(List.of(
            card("g1", "QUALIFICATION", UUID.randomUUID(), UUID.randomUUID()),
            card("p1", "PRIVATE", UUID.randomUUID(), UUID.randomUUID())));
        String token = "guest-token";
        UUID sessionId = UUID.randomUUID();
        guests.activeByToken.put(token, sessionId);

        GetFeedUseCase.FeedPage page = service.get(
            com.plantarena.shared.security.CurrentActor.guest(), token, 20, null);

        assertThat(page.items()).extracting(GetFeedUseCase.FeedItem::entryId)
            .containsExactly(idOf("g1"));

        assertThatThrownBy(() -> service.get(
            com.plantarena.shared.security.CurrentActor.guest(), null, 20, null))
            .isInstanceOf(NotIdentifiedException.class);
        assertThatThrownBy(() -> service.get(
            com.plantarena.shared.security.CurrentActor.guest(), "unknown", 20, null))
            .isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    @DisplayName("оцененные субъектом карточки исключаются (раздел 9)")
    void оцененные_исключаются() {
        cards.saveAll(List.of(
            card("g1", "QUALIFICATION", UUID.randomUUID(), UUID.randomUUID()),
            card("g2", "QUALIFICATION", UUID.randomUUID(), UUID.randomUUID())));
        voting.votedEntryIds.add(idOf("g1"));

        GetFeedUseCase.FeedPage page = service.get(
            com.plantarena.shared.security.CurrentActor.identified(viewer, Set.of()),
            null, 20, null);

        assertThat(page.items()).extracting(GetFeedUseCase.FeedItem::entryId)
            .containsExactly(idOf("g2"));
    }

    @Test
    @DisplayName("keyset: limit+1 → hasNext и nextCursor; продолжение не повторяет ключ")
    void keyset_пагинация() {
        cards.saveAll(List.of(
            card("g1", "QUALIFICATION", UUID.randomUUID(), UUID.randomUUID()),
            card("g2", "QUALIFICATION", UUID.randomUUID(), UUID.randomUUID()),
            card("g3", "QUALIFICATION", UUID.randomUUID(), UUID.randomUUID())));

        GetFeedUseCase.FeedPage page1 = service.get(
            com.plantarena.shared.security.CurrentActor.identified(viewer, Set.of()),
            null, 2, null);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.hasNext()).isTrue();
        assertThat(page1.nextCursor()).isNotBlank();

        GetFeedUseCase.FeedPage page2 = service.get(
            com.plantarena.shared.security.CurrentActor.identified(viewer, Set.of()),
            null, 2, page1.nextCursor());
        assertThat(page2.items()).extracting(GetFeedUseCase.FeedItem::entryId)
            .containsExactly(idOf("g3"));
        assertThat(page2.hasNext()).isFalse();
        assertThat(page2.nextCursor()).isNull();
    }

    @Test
    @DisplayName("курсор привязан к субъекту: чужой курсор — FeedCursorInvalidException")
    void чужой_курсор() {
        String strangerCursor = codec.encode(new FeedCursor(1L, NOW, null, null,
            "USER:" + stranger));

        assertThatThrownBy(() -> service.get(
            com.plantarena.shared.security.CurrentActor.identified(viewer, Set.of()),
            null, 20, strangerCursor))
            .isInstanceOf(FeedCursorInvalidException.class);
    }

    @Test
    @DisplayName("limit вне 1–50 — InvalidPaginationException (400)")
    void лимит() {
        assertThatThrownBy(() -> service.get(
            com.plantarena.shared.security.CurrentActor.identified(viewer, Set.of()),
            null, 0, null)).isInstanceOf(InvalidPaginationException.class);
        assertThatThrownBy(() -> service.get(
            com.plantarena.shared.security.CurrentActor.identified(viewer, Set.of()),
            null, 51, null)).isInstanceOf(InvalidPaginationException.class);
    }

    // ---------- helpers ----------

    private UUID idOf(String suffix) {
        return UUID.nameUUIDFromBytes(("feed-card-" + suffix).getBytes());
    }

    private FeedCard card(String suffix, String scope, UUID tournamentId, UUID owner) {
        UUID id = idOf(suffix);
        return new FeedCard(id, UUID.nameUUIDFromBytes(("w-" + suffix).getBytes()), tournamentId,
            scope, null, id, owner, UUID.randomUUID(), UUID.randomUUID(),
            "Фикус " + suffix, "Владелец " + suffix, NOW, NOW.plusSeconds(3600),
            NOW.minusSeconds(1), 0L);
    }
}
```

`src/test/java/com/plantarena/feed/application/FeedProjectionServiceTest.java`:

```java
package com.plantarena.feed.application;

import com.plantarena.feed.application.port.in.ProjectWindowUseCase;
import com.plantarena.feed.application.port.out.OwnerDirectory;
import com.plantarena.feed.application.port.out.PlantCatalog;
import com.plantarena.feed.application.support.InMemoryFeedCardRepository;
import com.plantarena.feed.domain.FeedCard;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проекция ленты (ADR-002): событие открытия окна пишет карточки с
 * обогащением из plants/identity; закрытие окна удаляет карточки.
 */
@DisplayName("FeedProjectionService: карточки по событиям окна")
class FeedProjectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private final InMemoryFeedCardRepository cards = new InMemoryFeedCardRepository();
    private final FeedProjectionService service = new FeedProjectionService(cards,
        new StubPlantCatalog(), new StubOwnerDirectory(),
        Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("открытие окна: карточки с title/assetId/displayName; неживое растение пропускается")
    void открытие_окна() {
        UUID aliveEntry = UUID.randomUUID();
        UUID deadEntry = UUID.randomUUID();
        UUID windowId = UUID.randomUUID();

        service.onWindowOpened(new ProjectWindowUseCase.WindowCardsCommand(windowId,
            UUID.randomUUID(), "QUALIFICATION", null, NOW.plusSeconds(600), List.of(
                new ProjectWindowUseCase.CardSeed(aliveEntry, UUID.randomUUID(),
                    UUID.nameUUIDFromBytes("plant-alive".getBytes()), NOW),
                new ProjectWindowUseCase.CardSeed(deadEntry, UUID.randomUUID(),
                    UUID.nameUUIDFromBytes("plant-dead".getBytes()), NOW))));

        List<FeedCard> saved = cards.page(new com.plantarena.feed.domain.FeedCardQuery(
            1L, NOW.plusSeconds(1), NOW, java.util.Set.of("QUALIFICATION"), java.util.Set.of(),
            null, java.util.Set.of(), null, null, 10));
        assertThat(saved).hasSize(1); // мёртвое растение не показывается
        assertThat(saved.get(0).entryId()).isEqualTo(aliveEntry);
        assertThat(saved.get(0).title()).isEqualTo("Живой фикус");
        assertThat(saved.get(0).ownerDisplayName()).isEqualTo("Владелец");
        assertThat(saved.get(0).closesAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    @DisplayName("закрытие окна: карточки окна удаляются (выжившие вернутся с новым окном)")
    void закрытие_окна() {
        UUID windowId = UUID.randomUUID();
        service.onWindowOpened(new ProjectWindowUseCase.WindowCardsCommand(windowId,
            UUID.randomUUID(), "FINAL", null, NOW.plusSeconds(600), List.of(
                new ProjectWindowUseCase.CardSeed(UUID.randomUUID(), UUID.randomUUID(),
                    UUID.nameUUIDFromBytes("plant-alive".getBytes()), NOW))));

        service.onWindowClosed(windowId);

        assertThat(cards.page(new com.plantarena.feed.domain.FeedCardQuery(
            1L, NOW.plusSeconds(1), NOW, java.util.Set.of("FINAL"), java.util.Set.of(),
            null, java.util.Set.of(), null, null, 10))).isEmpty();
    }

    private static final class StubPlantCatalog implements PlantCatalog {

        @Override
        public Optional<PlantView> findPlant(UUID plantId) {
            boolean alive = plantId.toString().endsWith("alive".substring(0, 0))
                || plantId.equals(UUID.nameUUIDFromBytes("plant-alive".getBytes()));
            return Optional.of(new PlantView(plantId, UUID.randomUUID(),
                alive ? "Живой фикус" : "Мёртвый фикус", alive ? "ALIVE" : "DEAD"));
        }
    }

    private static final class StubOwnerDirectory implements OwnerDirectory {

        @Override
        public Optional<OwnerView> findOwner(UUID userId) {
            return Optional.of(new OwnerView(userId, "Владелец"));
        }
    }
}
```

- [ ] **Step 2: Запустить — красный**

```bash
./mvnw -q test -Dtest='FeedCursorCodecTest,FeedServiceTest,FeedProjectionServiceTest' -DfailIfNoTests=false
```

Ожидание: FAIL (компиляция — классов нет).

- [ ] **Step 3: Реализация**

`src/main/java/com/plantarena/feed/application/FeedSettings.java`:

```java
package com.plantarena.feed.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Настройки ленты (раздел 9): секрет HMAC курсора и его TTL. */
@ConfigurationProperties(prefix = "plantarena.feed")
public record FeedSettings(String cursorSecret, Duration cursorTtl) {

    public FeedSettings {
        if (cursorSecret == null || cursorSecret.isBlank()) {
            throw new IllegalArgumentException("plantarena.feed.cursor-secret обязателен");
        }
        if (cursorTtl == null || cursorTtl.isNegative() || cursorTtl.isZero()) {
            throw new IllegalArgumentException("plantarena.feed.cursor-ttl > 0");
        }
    }
}
```

`src/main/java/com/plantarena/feed/application/FeedCursorInvalidException.java`:

```java
package com.plantarena.feed.application;

/** Невалидный/подменённый курсор ленты (раздел 9): 400 FEED_CURSOR_INVALID. */
public class FeedCursorInvalidException extends RuntimeException {

    public FeedCursorInvalidException(String message) {
        super(message);
    }
}
```

`src/main/java/com/plantarena/feed/application/FeedCursorExpiredException.java`:

```java
package com.plantarena.feed.application;

/** Истёкший курсор ленты (раздел 9): 410 FEED_CURSOR_EXPIRED, начать новую ленту. */
public class FeedCursorExpiredException extends RuntimeException {

    public FeedCursorExpiredException(String message) {
        super(message);
    }
}
```

`src/main/java/com/plantarena/feed/application/FeedCursorCodec.java`:

```java
package com.plantarena.feed.application;

import com.plantarena.feed.domain.FeedCursor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Подпись курсора ленты (раздел 9): base64url(payload).base64url(HMAC-SHA256).
 * Canonical: seed|cutoffEpochMilli|lastSortKey|lastId|subjectKey. Проверка —
 * пересчёт + MessageDigest.isEqual (постоянное время). TTL — от
 * snapshotCutoff: истёкший → 410 с предложением начать новую ленту.
 */
public class FeedCursorCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Duration ttl;

    public FeedCursorCodec(String secret, Duration ttl) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
    }

    public String encode(FeedCursor cursor) {
        String canonical = canonical(cursor);
        byte[] payload = canonical.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(hmac(canonical));
    }

    public FeedCursor decode(String encoded, Instant now) {
        if (encoded == null) {
            throw new FeedCursorInvalidException("Курсор отсутствует");
        }
        int dot = encoded.lastIndexOf('.');
        if (dot <= 0 || dot == encoded.length() - 1) {
            throw new FeedCursorInvalidException("Курсор повреждён");
        }
        byte[] payload;
        byte[] signature;
        try {
            payload = DECODER.decode(encoded.substring(0, dot));
            signature = DECODER.decode(encoded.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new FeedCursorInvalidException("Курсор повреждён");
        }
        String canonical = new String(payload, StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(hmac(canonical), signature)) {
            throw new FeedCursorInvalidException("Подпись курсора не сходится");
        }
        FeedCursor cursor = parse(canonical);
        if (now.isAfter(cursor.snapshotCutoff().plus(ttl))) {
            throw new FeedCursorExpiredException(
                "Курсор истёк — начните новую ленту (запрос без cursor)");
        }
        return cursor;
    }

    private FeedCursor parse(String canonical) {
        String[] parts = canonical.split("\\|", -1);
        if (parts.length != 5) {
            throw new FeedCursorInvalidException("Курсор повреждён");
        }
        try {
            long seed = Long.parseLong(parts[0]);
            Instant cutoff = Instant.ofEpochMilli(Long.parseLong(parts[1]));
            Long lastSortKey = parts[2].isEmpty() ? null : Long.parseLong(parts[2]);
            UUID lastId = parts[3].isEmpty() ? null : UUID.fromString(parts[3]);
            return new FeedCursor(seed, cutoff, lastSortKey, lastId, parts[4]);
        } catch (RuntimeException e) {
            throw new FeedCursorInvalidException("Курсор повреждён");
        }
    }

    private String canonical(FeedCursor cursor) {
        return cursor.seed() + "|" + cursor.snapshotCutoff().toEpochMilli() + "|"
            + (cursor.lastSortKey() == null ? "" : cursor.lastSortKey()) + "|"
            + (cursor.lastId() == null ? "" : cursor.lastId()) + "|" + cursor.subjectKey();
    }

    private byte[] hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 недоступен", e);
        }
    }
}
```

(импорт `java.util.UUID` добавить).

`src/main/java/com/plantarena/feed/application/port/in/GetFeedUseCase.java`:

```java
package com.plantarena.feed.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Лента (раздел 9): смешанные карточки для голосования, keyset-пагинация
 * без total. Субъект: пользователь (X-Demo-User-Id/JWT) или гость
 * (X-Guest-Token).
 */
public interface GetFeedUseCase {

    /**
     * @param guestToken сырое значение X-Guest-Token (игнорируется для
     *                   идентифицированного пользователя — раздел 9)
     * @param limit      1–50, по умолчанию 20
     * @param cursor     подписанный курсор предыдущей страницы или null
     */
    FeedPage get(CurrentActor actor, String guestToken, Integer limit, String cursor);

    record FeedPage(List<FeedItem> items, String nextCursor, boolean hasNext) {
    }

    record FeedItem(UUID windowId, String scope, UUID tournamentId, UUID entryId,
                    UUID plantId, String title, String imageUrl, UUID ownerId,
                    String ownerDisplayName, Instant closesAt) {
    }
}
```

`src/main/java/com/plantarena/feed/application/port/in/ProjectWindowUseCase.java`:

```java
package com.plantarena.feed.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Проекция ленты (ADR-002): применение событий окна (перевод события в команду feed). */
public interface ProjectWindowUseCase {

    void onWindowOpened(WindowCardsCommand command);

    void onWindowClosed(UUID windowId);

    record WindowCardsCommand(UUID windowId, UUID tournamentId, String scope, UUID clusterId,
                              Instant closesAt, List<CardSeed> cards) {
    }

    record CardSeed(UUID entryId, UUID userId, UUID plantId, Instant joinedAt) {
    }
}
```

`src/main/java/com/plantarena/feed/application/FeedService.java`:

```java
package com.plantarena.feed.application;

import com.plantarena.feed.application.port.in.GetFeedUseCase;
import com.plantarena.feed.application.port.out.GuestSessions;
import com.plantarena.feed.application.port.out.VotingDirectory;
import com.plantarena.feed.domain.FeedCard;
import com.plantarena.feed.domain.FeedCardQuery;
import com.plantarena.feed.domain.FeedCardRepository;
import com.plantarena.feed.domain.FeedCursor;
import com.plantarena.feed.domain.FeedOrdering;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import com.plantarena.shared.web.InvalidPaginationException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Лента (раздел 9): права и фильтры применяются повторно на каждой странице
 * (cursor не даёт прав), запрос limit+1 определяет hasNext, total не
 * возвращается. Псевдослучайный порядок — seed + стабильный id (SQL,
 * ADR-002); snapshotCutoff прячет новых участников до обновления ленты.
 */
@Service
public class FeedService implements GetFeedUseCase {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final Set<String> USER_SCOPES = Set.of("PRIVATE", "QUALIFICATION", "FINAL");
    private static final Set<String> GUEST_SCOPES = Set.of("QUALIFICATION", "FINAL");

    private final FeedCardRepository cards;
    private final VotingDirectory votingDirectory;
    private final GuestSessions guestSessions;
    private final FeedOrdering ordering;
    private final FeedCursorCodec cursorCodec;
    private final Clock clock;

    public FeedService(FeedCardRepository cards, VotingDirectory votingDirectory,
                       GuestSessions guestSessions, FeedOrdering ordering,
                       FeedCursorCodec cursorCodec, Clock clock) {
        this.cards = cards;
        this.votingDirectory = votingDirectory;
        this.guestSessions = guestSessions;
        this.ordering = ordering;
        this.cursorCodec = cursorCodec;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public FeedPage get(CurrentActor actor, String guestToken, Integer limit, String cursor) {
        int resolvedLimit = resolveLimit(limit);
        Subject subject = resolveSubject(actor, guestToken);
        FeedCursor current = resolveCursor(cursor, subject);
        FeedCardQuery query = new FeedCardQuery(current.seed(), current.snapshotCutoff(),
            clock.instant(), subject.scopes(), subject.participatedTournamentIds(),
            subject.excludedOwnerUserId(),
            votingDirectory.findVotedEntryIdsInOpenWindows(subject.subjectKey()),
            current.lastSortKey(), current.lastId(), resolvedLimit + 1);
        List<FeedCard> page = cards.page(query);
        boolean hasNext = page.size() > resolvedLimit;
        List<FeedCard> visible = hasNext ? page.subList(0, resolvedLimit) : page;
        String nextCursor = null;
        if (hasNext) {
            FeedCard last = visible.get(visible.size() - 1);
            nextCursor = cursorCodec.encode(new FeedCursor(current.seed(),
                current.snapshotCutoff(), last.sortKey(), last.id(), subject.subjectKey()));
        }
        return new FeedPage(visible.stream().map(this::toItem).toList(), nextCursor, hasNext);
    }

    private int resolveLimit(Integer limit) {
        int resolved = limit == null ? DEFAULT_LIMIT : limit;
        if (resolved < 1 || resolved > MAX_LIMIT) {
            throw new InvalidPaginationException(
                "limit вне диапазона: " + resolved + " (допустимо 1–" + MAX_LIMIT + ")");
        }
        return resolved;
    }

    private Subject resolveSubject(CurrentActor actor, String guestToken) {
        if (actor != null && !actor.isGuest()) {
            return new Subject("USER:" + actor.userId(), USER_SCOPES,
                votingDirectory.findParticipatedTournamentIds(actor.userId()),
                actor.userId());
        }
        String token = guestToken == null ? "" : guestToken.trim();
        if (token.isEmpty()) {
            throw new NotIdentifiedException(
                "Лента требует пользователя или гостевой токен (X-Guest-Token)");
        }
        UUID sessionId = guestSessions.activeSessionId(token)
            .orElseThrow(() -> new NotIdentifiedException(
                "Гостевая сессия отсутствует или истекла"));
        return new Subject("GUEST:" + sessionId, GUEST_SCOPES, Set.of(), null);
    }

    private FeedCursor resolveCursor(String cursor, Subject subject) {
        if (cursor == null || cursor.isBlank()) {
            return new FeedCursor(ordering.newSeed(), clock.instant(), null, null,
                subject.subjectKey());
        }
        FeedCursor decoded = cursorCodec.decode(cursor, clock.instant());
        if (!decoded.subjectKey().equals(subject.subjectKey())) {
            throw new FeedCursorInvalidException(
                "Курсор выдан другому субъекту — начните свою ленту");
        }
        return decoded;
    }

    private GetFeedUseCase.FeedItem toItem(FeedCard card) {
        return new GetFeedUseCase.FeedItem(card.windowId(), card.scope(), card.tournamentId(),
            card.entryId(), card.plantId(), card.title(),
            "/api/v1/files/" + card.assetId(), card.userId(), card.ownerDisplayName(),
            card.closesAt());
    }

    private record Subject(String subjectKey, Set<String> scopes,
                           Set<UUID> participatedTournamentIds, UUID excludedOwnerUserId) {
    }
}
```

`src/main/java/com/plantarena/feed/application/FeedProjectionService.java`:

```java
package com.plantarena.feed.application;

import com.plantarena.feed.application.port.in.ProjectWindowUseCase;
import com.plantarena.feed.application.port.out.OwnerDirectory;
import com.plantarena.feed.application.port.out.PlantCatalog;
import com.plantarena.feed.domain.FeedCard;
import com.plantarena.feed.domain.FeedCardRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Проекция ленты (ADR-002): событие открытия окна → карточки (обогащение
 * title/assetId из plants, displayName из identity — снимок на момент
 * открытия); закрытие окна → удаление карточек. Вызывается синхронным
 * @EventListener в tx издателя (как PlantModerationDecidedHandler).
 */
@Service
public class FeedProjectionService implements ProjectWindowUseCase {

    private static final Logger log = LoggerFactory.getLogger(FeedProjectionService.class);

    private final FeedCardRepository cards;
    private final PlantCatalog plantCatalog;
    private final OwnerDirectory ownerDirectory;
    private final Clock clock;

    public FeedProjectionService(FeedCardRepository cards, PlantCatalog plantCatalog,
                                 OwnerDirectory ownerDirectory, Clock clock) {
        this.cards = cards;
        this.plantCatalog = plantCatalog;
        this.ownerDirectory = ownerDirectory;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void onWindowOpened(ProjectWindowUseCase.WindowCardsCommand command) {
        Instant now = clock.instant();
        List<FeedCard> newCards = new ArrayList<>(command.cards().size());
        for (ProjectWindowUseCase.CardSeed seed : command.cards()) {
            PlantCatalog.PlantView plant = plantCatalog.findPlant(seed.plantId()).orElse(null);
            if (plant == null || !"ALIVE".equals(plant.lifeStatus())) {
                log.warn("Карточка {} пропущена: растение {} отсутствует или не живое",
                    seed.entryId(), seed.plantId());
                continue;
            }
            String displayName = ownerDirectory.findOwner(seed.userId())
                .map(OwnerDirectory.OwnerView::displayName)
                .orElse("Участник");
            newCards.add(new FeedCard(UUID.randomUUID(), command.windowId(),
                command.tournamentId(), command.scope(), command.clusterId(), seed.entryId(),
                seed.userId(), seed.plantId(), plant.assetId(), plant.title(), displayName,
                seed.joinedAt(), command.closesAt(), now, 0L));
        }
        if (!newCards.isEmpty()) {
            cards.saveAll(newCards);
        }
    }

    @Override
    @Transactional
    public void onWindowClosed(UUID windowId) {
        cards.deleteAllByWindowId(windowId);
    }
}
```

- [ ] **Step 4: ACL-адаптеры, обработчики событий, exception handler, wiring**

`src/main/java/com/plantarena/feed/adapter/out/tournaments/InProcessVotingDirectory.java`:

```java
package com.plantarena.feed.adapter.out.tournaments;

import com.plantarena.feed.application.port.out.VotingDirectory;
import com.plantarena.tournaments.api.FeedDirectory;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** ACL: read-контракт tournaments → порт feed (раздел 4.3, in-process). */
@Component
public class InProcessVotingDirectory implements VotingDirectory {

    private final FeedDirectory feedDirectory;

    public InProcessVotingDirectory(FeedDirectory feedDirectory) {
        this.feedDirectory = feedDirectory;
    }

    @Override
    public Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey) {
        return feedDirectory.findVotedEntryIdsInOpenWindows(subjectKey);
    }

    @Override
    public Set<UUID> findParticipatedTournamentIds(UUID userId) {
        return feedDirectory.findParticipatedTournamentIds(userId);
    }
}
```

`src/main/java/com/plantarena/feed/adapter/out/tournaments/InProcessGuestSessions.java`:

```java
package com.plantarena.feed.adapter.out.tournaments;

import com.plantarena.feed.application.port.out.GuestSessions;
import com.plantarena.tournaments.api.GuestSessionDirectory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** ACL: read-контракт гостевых сессий tournaments → порт feed (in-process). */
@Component
public class InProcessGuestSessions implements GuestSessions {

    private final GuestSessionDirectory guestSessionDirectory;

    public InProcessGuestSessions(GuestSessionDirectory guestSessionDirectory) {
        this.guestSessionDirectory = guestSessionDirectory;
    }

    @Override
    public Optional<UUID> activeSessionId(String rawToken) {
        return guestSessionDirectory.activeSessionId(rawToken);
    }
}
```

`src/main/java/com/plantarena/feed/adapter/out/plants/InProcessPlantCatalog.java`:

```java
package com.plantarena.feed.adapter.out.plants;

import com.plantarena.feed.application.port.out.PlantCatalog;
import com.plantarena.plants.api.PlantDirectory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** ACL: публичные данные растения plants → порт feed (in-process). */
@Component
public class InProcessPlantCatalog implements PlantCatalog {

    private final PlantDirectory plantDirectory;

    public InProcessPlantCatalog(PlantDirectory plantDirectory) {
        this.plantDirectory = plantDirectory;
    }

    @Override
    public Optional<PlantView> findPlant(UUID plantId) {
        return plantDirectory.findById(plantId).map(plant -> new PlantView(plant.id(),
            plant.assetId(), plant.title(), plant.lifeStatus().name()));
    }
}
```

`src/main/java/com/plantarena/feed/adapter/out/identity/InProcessOwnerDirectory.java`:

```java
package com.plantarena.feed.adapter.out.identity;

import com.plantarena.feed.application.port.out.OwnerDirectory;
import com.plantarena.identity.api.UserDirectory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** ACL: публичный профиль identity → порт feed (in-process, без email/координат). */
@Component
public class InProcessOwnerDirectory implements OwnerDirectory {

    private final UserDirectory userDirectory;

    public InProcessOwnerDirectory(UserDirectory userDirectory) {
        this.userDirectory = userDirectory;
    }

    @Override
    public Optional<OwnerView> findOwner(UUID userId) {
        return userDirectory.findById(userId)
            .map(user -> new OwnerView(user.id(), user.displayName()));
    }
}
```

`src/main/java/com/plantarena/feed/adapter/in/events/VotingWindowOpenedHandler.java`:

```java
package com.plantarena.feed.adapter.in.events;

import com.plantarena.feed.application.port.in.ProjectWindowUseCase;
import com.plantarena.tournaments.api.event.VotingWindowOpenedEvent;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Подписка на открытие окна (раздел 9): перевод опубликованного события в
 * команду проекции. Синхронно в tx издателя — карточки появляются вместе
 * с окном (обязательное последствие, раздел 10.3).
 */
@Component
public class VotingWindowOpenedHandler {

    private final ProjectWindowUseCase projection;

    public VotingWindowOpenedHandler(ProjectWindowUseCase projection) {
        this.projection = projection;
    }

    @EventListener
    public void on(VotingWindowOpenedEvent event) {
        projection.onWindowOpened(new ProjectWindowUseCase.WindowCardsCommand(
            event.payload().windowId(), event.payload().tournamentId(),
            event.payload().scope(), event.payload().clusterId(),
            event.payload().closesAt(),
            event.payload().participants().stream()
                .map(p -> new ProjectWindowUseCase.CardSeed(p.entryId(), p.userId(),
                    p.plantId(), p.joinedAt()))
                .toList()));
    }
}
```

`src/main/java/com/plantarena/feed/adapter/in/events/VotingWindowClosedHandler.java`:

```java
package com.plantarena.feed.adapter.in.events;

import com.plantarena.feed.application.port.in.ProjectWindowUseCase;
import com.plantarena.tournaments.api.event.VotingWindowClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Подписка на закрытие окна (раздел 9): карточки окна удаляются. */
@Component
public class VotingWindowClosedHandler {

    private final ProjectWindowUseCase projection;

    public VotingWindowClosedHandler(ProjectWindowUseCase projection) {
        this.projection = projection;
    }

    @EventListener
    public void on(VotingWindowClosedEvent event) {
        projection.onWindowClosed(event.payload().windowId());
    }
}
```

`src/main/java/com/plantarena/feed/adapter/in/web/FeedExceptionHandler.java`:

```java
package com.plantarena.feed.adapter.in.web;

import com.plantarena.feed.application.FeedCursorExpiredException;
import com.plantarena.feed.application.FeedCursorInvalidException;
import com.plantarena.shared.web.ApiError;
import com.plantarena.shared.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Перевод исключений feed (раздел 13): невалидный курсор — 400, истёкший —
 * 410 (начать новую ленту). HIGHEST_PRECEDENCE — раньше catch-all shared.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FeedExceptionHandler {

    @ExceptionHandler(FeedCursorInvalidException.class)
    public ResponseEntity<ApiError> cursorInvalid(FeedCursorInvalidException e,
                                                  HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "FEED_CURSOR_INVALID", e.getMessage(), request);
    }

    @ExceptionHandler(FeedCursorExpiredException.class)
    public ResponseEntity<ApiError> cursorExpired(FeedCursorExpiredException e,
                                                  HttpServletRequest request) {
        return respond(HttpStatus.GONE, "FEED_CURSOR_EXPIRED", e.getMessage(), request);
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

`src/main/java/com/plantarena/config/FeedWiringConfig.java`:

```java
package com.plantarena.config;

import com.plantarena.feed.application.FeedCursorCodec;
import com.plantarena.feed.application.FeedSettings;
import com.plantarena.feed.domain.FeedOrdering;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Связывание контекста feed: настройки, подпись курсора, seed порядка. */
@Configuration
@EnableConfigurationProperties(FeedSettings.class)
public class FeedWiringConfig {

    @Bean
    public FeedCursorCodec feedCursorCodec(FeedSettings settings) {
        return new FeedCursorCodec(settings.cursorSecret(), settings.cursorTtl());
    }

    @Bean
    public FeedOrdering feedOrdering() {
        return new FeedOrdering();
    }
}
```

`src/main/resources/application.yml` — в блок `plantarena:` добавить:

```yaml
  feed:
    cursor-secret: ${FEED_CURSOR_SECRET:dev-only-cursor-secret} # HMAC курсора ленты (раздел 9)
    cursor-ttl: 1h # срок действия курсора; истёкший — 410 с новой лентой
```

- [ ] **Step 5: Запустить — зелёный, затем весь verify**

```bash
./mvnw -q test -Dtest='FeedCursorCodecTest,FeedServiceTest,FeedProjectionServiceTest' -DfailIfNoTests=false
./mvnw -q verify
```

Ожидание: все зелёные; ArchUnit подтверждает границы feed (адаптеры → только `api` чужих контекстов).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/plantarena/feed/application/ \
  src/main/java/com/plantarena/feed/adapter/in/events/ \
  src/main/java/com/plantarena/feed/adapter/in/web/FeedExceptionHandler.java \
  src/main/java/com/plantarena/feed/adapter/out/tournaments/ \
  src/main/java/com/plantarena/feed/adapter/out/plants/ \
  src/main/java/com/plantarena/feed/adapter/out/identity/ \
  src/main/java/com/plantarena/config/FeedWiringConfig.java \
  src/main/resources/application.yml \
  src/test/java/com/plantarena/feed/application/
git commit -m "feat(feed): FeedService (права, keyset, курсор HMAC), проекция по событиям, ACL и wiring"
```

---

### Task 9: Голосование гостя — `VotingService`, контроллер, Swagger

**Files:**
- Modify: `src/main/java/com/plantarena/tournaments/application/port/in/VotingUseCase.java`
- Modify: `src/main/java/com/plantarena/tournaments/application/VotingService.java`
- Modify: `src/main/java/com/plantarena/tournaments/adapter/in/web/VotingController.java`
- Modify: `src/main/java/com/plantarena/config/OpenApiConfig.java`
- Test: `src/test/java/com/plantarena/tournaments/application/VotingServiceTest.java` (дополнение + фиксация сигнатур)

**Interfaces:**
- Consumes: `GuestSessionRepository`, `GuestTokens`, `FixedWindowRateLimiter`, `GuestSessionsSettings`, `AbuseSignals` (Tasks 2–4), `VotingSubject.guest` (Task 2).
- Produces (для Task 10): `VotingUseCase { long cast(CurrentActor actor, String guestToken, UUID windowId, UUID entryId, String value); long remove(CurrentActor actor, String guestToken, UUID windowId, UUID entryId); Optional<String> myVote(CurrentActor actor, String guestToken, UUID windowId, UUID entryId); }`; заголовок `X-Guest-Token` в Swagger всех операций.

- [ ] **Step 1: Красные тесты (дополнения в `VotingServiceTest`)**

Существующие вызовы `service.cast(actor, windowId, entryId, value)` / `remove` / `myVote` по всему классу дополнить вторым аргументом `null` (гость без токена). Конструктор `service` дополнить зависимостями:

```java
    private final InMemoryGuestSessionRepository guestSessions = new InMemoryGuestSessionRepository();
    private final FixedWindowRateLimiter rateLimiter = new FixedWindowRateLimiter(clock);
    private final GuestSessionsSettings guestSettings = new GuestSessionsSettings(
        Duration.ofHours(24), 10, 30);
    private final RecordingAbuseSignals abuseSignals = new RecordingAbuseSignals();
```

(если `clock` в классе — `Clock.fixed`, использовать его; `RecordingAbuseSignals` — как в `GuestSessionServiceTest`). Существующие тесты «гость — 401» остаются зелёными (гость без токена → 401). Добавить тесты:

```java
    @Test
    @DisplayName("гость с активной сессией голосует в глобальном окне: субъект GUEST:<sessionId>")
    void гость_голосует_в_глобальном() {
        // фикстуры класса: квалификационное окно с entry другого пользователя
        UUID sessionId = UUID.randomUUID();
        String token = GuestTokens.newToken();
        guestSessions.save(GuestSession.issue(sessionId, GuestTokens.sha256Hex(token),
            NOW, Duration.ofHours(1)));
        CurrentActor guest = CurrentActor.guest();

        long score = service.cast(guest, token, globalWindowId, globalEntryId, "LIKE");

        assertThat(score).isEqualTo(1L);
        assertThat(windows.findById(globalWindowId).orElseThrow()
                .myVote("GUEST:" + sessionId, globalEntryId))
            .isEqualTo(VoteValue.LIKE);
    }

    @Test
    @DisplayName("гость не голосует в закрытом окне: 404 (турнир скрыт, раздел 13)")
    void гость_в_закрытом_404() {
        String token = GuestTokens.newToken();
        guestSessions.save(GuestSession.issue(UUID.randomUUID(), GuestTokens.sha256Hex(token),
            NOW, Duration.ofHours(1)));

        assertThatThrownBy(() -> service.cast(CurrentActor.guest(), token, privateWindowId,
                privateEntryId, "LIKE"))
            .isInstanceOf(TournamentNotFoundException.class);
    }

    @Test
    @DisplayName("истёкшая сессия и мусорный токен — 401")
    void истёкшая_сессия() {
        String expired = GuestTokens.newToken();
        guestSessions.save(GuestSession.issue(UUID.randomUUID(),
            GuestTokens.sha256Hex(expired), NOW.minus(Duration.ofHours(2)),
            Duration.ofHours(1)));

        assertThatThrownBy(() -> service.cast(CurrentActor.guest(), expired, globalWindowId,
                globalEntryId, "LIKE")).isInstanceOf(NotIdentifiedException.class);
        assertThatThrownBy(() -> service.cast(CurrentActor.guest(), "garbage", globalWindowId,
                globalEntryId, "LIKE")).isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    @DisplayName("идентифицированный пользователь при обоих заголовках — субъект USER (раздел 9)")
    void пользователь_при_гостевом_токене() {
        String token = GuestTokens.newToken();
        guestSessions.save(GuestSession.issue(UUID.randomUUID(), GuestTokens.sha256Hex(token),
            NOW, Duration.ofHours(1)));

        service.cast(identifiedViewer, token, globalWindowId, globalEntryId, "LIKE");

        assertThat(windows.findById(globalWindowId).orElseThrow()
                .myVote("USER:" + identifiedViewer.userId(), globalEntryId))
            .isEqualTo(VoteValue.LIKE);
    }

    @Test
    @DisplayName("лимит голосов гостя на сессию — RateLimitExceededException (429)")
    void лимит_голосов_гостя() {
        UUID sessionId = UUID.randomUUID();
        String token = GuestTokens.newToken();
        guestSessions.save(GuestSession.issue(sessionId, GuestTokens.sha256Hex(token),
            NOW, Duration.ofHours(1)));
        GuestSessionsSettings limit1 = new GuestSessionsSettings(Duration.ofHours(24), 10, 1);
        VotingService limited = new VotingService(windows, tournaments, invitations, entries,
            guestSessions, accessPolicy, rateLimiter, limit1, abuseSignals, clock);

        limited.cast(CurrentActor.guest(), token, globalWindowId, globalEntryId, "LIKE");
        assertThatThrownBy(() -> limited.cast(CurrentActor.guest(), token, globalWindowId,
                globalEntryId2, "LIKE"))
            .isInstanceOf(RateLimitExceededException.class);
        assertThat(abuseSignals.lastAction).isEqualTo("guest-vote-limit");
    }
```

(`globalWindowId`/`globalEntryId`/`privateWindowId`/`privateEntryId`/`identifiedViewer` — построить по фикстурам класса: квалификационное окно `VotingWindow.openQualification(...)` и приватное `VotingWindow.open(...)` с сохранением в `windows`; имена — по существующим фикстурам класса.)

- [ ] **Step 2: Запустить — красный**

```bash
./mvnw -q test -Dtest='VotingServiceTest' -DfailIfNoTests=false
```

Ожидание: FAIL (компиляция — сигнатуры без guestToken).

- [ ] **Step 3: Реализация**

`src/main/java/com/plantarena/tournaments/application/port/in/VotingUseCase.java` — заменить целиком (сохранить javadoc-стиль):

```java
package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.Optional;
import java.util.UUID;

/**
 * Голосование (раздел 9): субъект — USER всегда для идентифицированного
 * (гостевой токен игнорируется) или GUEST по X-Guest-Token (только
 * глобальные окна). my-vote — те же правила.
 */
public interface VotingUseCase {

    long cast(CurrentActor actor, String guestToken, UUID windowId, UUID entryId, String value);

    long remove(CurrentActor actor, String guestToken, UUID windowId, UUID entryId);

    Optional<String> myVote(CurrentActor actor, String guestToken, UUID windowId, UUID entryId);
}
```

`VotingService.java`: импорты добавить:

```java
import com.plantarena.tournaments.domain.GuestSession;
import com.plantarena.tournaments.domain.GuestSessionRepository;
```

конструктор и поля дополнить (`guestSessions`, `rateLimiter`, `guestSettings`, `abuseSignals` — типы `GuestSessionRepository`, `FixedWindowRateLimiter`, `GuestSessionsSettings`, `AbuseSignals`); методы заменить:

```java
    @Override
    @Transactional
    public long cast(CurrentActor actor, String guestToken, UUID windowId, UUID entryId,
                     String value) {
        VotingSubject subject = subjectOf(actor, guestToken);
        VoteValue voteValue = parseValue(value);
        VotingWindow window = lock(windowId);
        requireVoter(actor, findTournament(window.tournamentId()), window);
        checkVote(window, entryId, actor);
        long score = window.castVote(subject, entryId, voteValue, clock.instant());
        windows.save(window);
        return score;
    }

    @Override
    @Transactional
    public long remove(CurrentActor actor, String guestToken, UUID windowId, UUID entryId) {
        VotingSubject subject = subjectOf(actor, guestToken);
        VotingWindow window = lock(windowId);
        requireVoter(actor, findTournament(window.tournamentId()), window);
        checkVote(window, entryId, actor);
        long score = window.removeVote(subject, entryId, clock.instant());
        windows.save(window);
        return score;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> myVote(CurrentActor actor, String guestToken, UUID windowId,
                                   UUID entryId) {
        VotingSubject subject = subjectOf(actor, guestToken);
        VotingWindow window = windows.findById(windowId)
            .orElseThrow(() -> new WindowNotFoundException("Окно не найдено: " + windowId));
        requireVoter(actor, findTournament(window.tournamentId()), window);
        if (!window.hasEntry(entryId)) {
            throw new EntryNotInWindowException("Участие не входит в окно: " + entryId);
        }
        VoteValue value = window.myVote(subject.subjectKey(), entryId);
        return Optional.ofNullable(value).map(Enum::name);
    }
```

`subjectOf` и `requireVoter` заменить на:

```java
    /**
     * Субъект голосования (раздел 9): идентифицированный — USER всегда
     * (гостевой токен игнорируется); гость — GUEST по активной сессии
     * (X-Guest-Token), с лимитом голосов на сессию (429).
     */
    private VotingSubject subjectOf(CurrentActor actor, String guestToken) {
        if (actor != null && !actor.isGuest()) {
            return VotingSubject.user(actor.userId());
        }
        String token = guestToken == null ? "" : guestToken.trim();
        if (token.isEmpty()) {
            throw new NotIdentifiedException(
                "Голосование требует пользователя или гостевой токен (X-Guest-Token)");
        }
        GuestSession session = guestSessions.findByTokenHash(GuestTokens.sha256Hex(token))
            .filter(active -> active.isActive(clock.instant()))
            .orElseThrow(() -> new NotIdentifiedException(
                "Гостевая сессия отсутствует или истекла"));
        try {
            rateLimiter.check("guest-vote:" + session.id(),
                guestSettings.voteLimitPerMinute());
        } catch (RateLimitExceededException e) {
            abuseSignals.signal("guest-vote-limit", "sessionId=" + session.id());
            throw e;
        }
        return VotingSubject.guest(session.id());
    }

    private void requireVoter(CurrentActor actor, Tournament tournament, VotingWindow window) {
        if (window.scope() == WindowScope.PRIVATE) {
            if (actor == null || actor.isGuest()) {
                // закрытый турнир скрыт от гостя (раздел 13: 404, не 401/403)
                throw new TournamentNotFoundException(
                    "Турнир не найден: " + tournament.id());
            }
            accessPolicy.requireTournamentViewer(actor, tournament,
                visibleBeyondOrganizer(actor, tournament.id()));
            if (!entries.existsByTournamentIdAndUserId(tournament.id(), actor.userId())) {
                throw new AccessDeniedException(
                    "Голосовать может только участник, допущенный к старту (допущение 9)");
            }
            return;
        }
        // глобальные окна (раздел 2): любой идентифицированный или гость
        // с активной сессией — субъект уже проверен subjectOf
    }
```

`checkVote` — без изменений (для гостя `actor.userId() == null`, `equals(null) == false`; самоголосование гостя неприменимо — допущение 6).

`VotingController.java`: во все три метода добавить параметр и передачу (пример для cast; remove/myVote — аналогично):

```java
    @PutMapping("/{windowId}/entries/{entryId}/vote")
    @Operation(operationId = "voting-cast-vote",
        summary = "Установить голос LIKE/DISLIKE (участник турнира или гость в глобальном окне)")
    public ResponseEntity<VoteResponse> cast(@PathVariable UUID windowId,
                                             @PathVariable UUID entryId,
                                             @RequestHeader(value = "X-Guest-Token",
                                                 required = false) String guestToken,
                                             @Valid @RequestBody VoteRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        long score = voting.cast(actor, guestToken, windowId, entryId, request.value());
        return ResponseEntity.ok(new VoteResponse(score));
    }
```

(импорт `org.springframework.web.bind.annotation.RequestHeader`).

`OpenApiConfig.java`: добавить второй бин (по образцу `demoUserIdHeaderCustomizer`):

```java
    /**
     * Заголовок гостевого токена (раздел 9): X-Guest-Token от POST
     * /guest-sessions; для идентифицированного пользователя игнорируется.
     */
    @Bean
    public OperationCustomizer guestTokenHeaderCustomizer() {
        return (Operation operation, org.springframework.web.method.HandlerMethod handlerMethod) -> {
            operation.addParametersItem(new Parameter()
                .in("header")
                .name("X-Guest-Token")
                .description("Гостевой токен (POST /guest-sessions): голосование и лента в "
                    + "глобальных окнах. Для идентифицированного пользователя игнорируется.")
                .required(false)
                .schema(new StringSchema()));
            return operation;
        };
    }
```

- [ ] **Step 4: Запустить — зелёный, затем весь verify**

```bash
./mvnw -q test -Dtest='VotingServiceTest' -DfailIfNoTests=false
./mvnw -q verify
```

Ожидание: `GlobalApiIT` остаётся зелёным (гость без токена — по-прежнему 401); все существующие HTTP-вызовы голосования без `X-Guest-Token` не меняют поведение.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/plantarena/tournaments/application/port/in/VotingUseCase.java \
  src/main/java/com/plantarena/tournaments/application/VotingService.java \
  src/main/java/com/plantarena/tournaments/adapter/in/web/VotingController.java \
  src/main/java/com/plantarena/config/OpenApiConfig.java \
  src/test/java/com/plantarena/tournaments/application/VotingServiceTest.java
git commit -m "feat(voting): субъект GUEST по X-Guest-Token — только глобальные окна, лимит 429"
```

---

### Task 10: `FeedController`, зелёные приёмочные

**Files:**
- Create: `src/main/java/com/plantarena/feed/adapter/in/web/FeedController.java`
- Create: `src/main/java/com/plantarena/feed/adapter/in/web/FeedItemResponse.java`
- Create: `src/main/java/com/plantarena/feed/adapter/in/web/FeedPageResponse.java`
- Create: `src/main/java/com/plantarena/feed/adapter/in/web/OwnerResponse.java`

**Interfaces:**
- Consumes: `GetFeedUseCase` (Task 8), `CurrentActorProvider`, `FeedExceptionHandler` (Task 8).
- Produces: REST `GET /api/v1/feed?limit&cursor` → 200 `{items, nextCursor, hasNext}` (без total); operationId `feed-get`.

- [ ] **Step 1: Контроллер и DTO**

`src/main/java/com/plantarena/feed/adapter/in/web/OwnerResponse.java`:

```java
package com.plantarena.feed.adapter.in.web;

import java.util.UUID;

/** Публичный минимум о владельце карточки (раздел 9: не приватный профиль). */
public record OwnerResponse(UUID userId, String displayName) {
}
```

`src/main/java/com/plantarena/feed/adapter/in/web/FeedItemResponse.java`:

```java
package com.plantarena.feed.adapter.in.web;

import java.time.Instant;
import java.util.UUID;

/** Карточка ленты (раздел 9): окно, участие, растение, публичный владелец. */
public record FeedItemResponse(
        UUID windowId,
        String scope,
        UUID tournamentId,
        UUID entryId,
        UUID plantId,
        String title,
        String imageUrl,
        OwnerResponse owner,
        Instant closesAt) {
}
```

`src/main/java/com/plantarena/feed/adapter/in/web/FeedPageResponse.java`:

```java
package com.plantarena.feed.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Страница ленты (раздел 13): keyset, без total; nextCursor null на последней странице. */
public record FeedPageResponse(
        List<FeedItemResponse> items,
        @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor,
        boolean hasNext) {
}
```

`src/main/java/com/plantarena/feed/adapter/in/web/FeedController.java`:

```java
package com.plantarena.feed.adapter.in.web;

import com.plantarena.feed.application.port.in.GetFeedUseCase;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Лента (раздел 13): бесконечная прокрутка keyset-курсором без total.
 * Субъект — пользователь (X-Demo-User-Id в dev/test) либо гость
 * (X-Guest-Token; только глобальные карточки).
 */
@RestController
@Tag(name = "feed")
public class FeedController {

    private final GetFeedUseCase getFeed;
    private final CurrentActorProvider currentActorProvider;

    public FeedController(GetFeedUseCase getFeed, CurrentActorProvider currentActorProvider) {
        this.getFeed = getFeed;
        this.currentActorProvider = currentActorProvider;
    }

    @GetMapping("/api/v1/feed")
    @Operation(operationId = "feed-get",
        summary = "Лента карточек для голосования: {items,nextCursor,hasNext} без total; "
            + "невалидный курсор — 400, истёкший — 410")
    public ResponseEntity<FeedPageResponse> get(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        CurrentActor actor = currentActorProvider.currentActor();
        GetFeedUseCase.FeedPage page = getFeed.get(actor, guestToken, limit, cursor);
        return ResponseEntity.ok(new FeedPageResponse(
            page.items().stream()
                .map(item -> new FeedItemResponse(item.windowId(), item.scope(),
                    item.tournamentId(), item.entryId(), item.plantId(), item.title(),
                    item.imageUrl(), new OwnerResponse(item.ownerId(),
                        item.ownerDisplayName()), item.closesAt()))
                .toList(),
            page.nextCursor(), page.hasNext()));
    }
}
```

- [ ] **Step 2: Зелёные приёмочные и весь verify**

```bash
./mvnw -q verify -Dit.test='FeedApiIT,FeedCursorApiIT' -DfailIfNoTests=false
./mvnw -q verify
```

Ожидание: `FeedApiIT` и `FeedCursorApiIT` зелёные (лента, гости, курсор, 410/400, голосование гостя, закрытие окна); весь verify зелёный, включая ArchUnit и JaCoCo gate.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/plantarena/feed/adapter/in/web/FeedController.java \
  src/main/java/com/plantarena/feed/adapter/in/web/FeedItemResponse.java \
  src/main/java/com/plantarena/feed/adapter/in/web/FeedPageResponse.java \
  src/main/java/com/plantarena/feed/adapter/in/web/OwnerResponse.java
git commit -m "feat(feed): REST GET /feed — keyset-пагинация без total; приёмочные FeedApiIT/FeedCursorApiIT зелёные"
```

---

### Task 11: Docs, ENV, финальный verify, merge

**Files:**
- Create: `docs/domain/adr/ADR-013-guest-sessions.md`
- Modify: `docs/domain/adr/ADR-002-feed-own-projection.md`
- Modify: `docs/domain/glossary.md`
- Modify: `docs/domain/aggregates.md`
- Modify: `docs/domain/context-map.md`
- Modify: `README.md`
- Modify: `.env.example`
- Modify: `docker-compose.yml`

**Interfaces:**
- Consumes: всё вышеперечисленное.
- Produces: документация итерации; зелёный `./mvnw verify`; merge `feat/iteration-8-feed` → `main`.

- [ ] **Step 1: ADR-013 и статус ADR-002**

`docs/domain/adr/ADR-013-guest-sessions.md`:

```markdown
# ADR-013: Гостевые сессии и минимальная защита от накрутки

Статус: принято (итерация 8).

## Контекст

Голосование в глобальном турнире доступно гостям (раздел 2/9 требований):
нужен анонимный субъект без регистрации, при этом клиент не должен иметь
возможности назначить себе произвольный субъект. Раздел 9 требует:
хранить только хэш токена, лимиты выдачи сессий и голосов с 429 +
Retry-After, журнал подозрительных действий за портом `AbuseSignals`.

## Решение

- **`GuestSession` — агрегат tournaments** (таблица `guest_session` в схеме
  tournaments, раздел 11): id, tokenHash (SHA-256 hex, UNIQUE), createdAt,
  expiresAt. Токен — 32 байта SecureRandom → Base64URL, возвращается один
  раз в ответе `POST /api/v1/guest-sessions`; нигде больше не существует.
  TTL — `plantarena.guests.session-ttl` (24h). Активность `now < expiresAt`.
- **Субъект `GUEST:<sessionId>`**: `VotingSubject.guest(...)`; уникальность
  голоса `(окно, entry, subjectKey)` уже не зависит от типа субъекта.
  Идентифицированный пользователь всегда USER, даже при переданном
  `X-Guest-Token` (раздел 9). Самоголосование к гостю неприменимо
  (допущение 6): гость не владеет участиями.
- **Разрешение гостя — в use case** (не в `CurrentActor`): гостевые сессии —
  понятие tournaments, identity не может их проверять (направление
  зависимостей tournaments → identity). Контроллеры передают сырое значение
  `X-Guest-Token` в use case; `VotingService`/`FeedService` разрешают
  субъекта (неизвестный/истёкший токен → 401). PRIVATE-окно для гостя —
  404 (скрыт политикой приватности).
- **Лимиты — in-memory фиксированное окно 1 минута**
  (`FixedWindowRateLimiter`): выдача сессий на IP
  (`plantarena.guests.session-creation-limit-per-minute`), голоса гостя на
  сессию (`plantarena.guests.vote-limit-per-minute`). Превышение — 429
  `RATE_LIMITED` + `Retry-After`. Состояние сбрасывается рестартом —
  осознанная минимальность. Журнал — порт `AbuseSignals` (логирующий
  адаптер; anti-doping-service — лаба №4). IP — дополнительный сигнал,
  не идентификатор человека.

## Последствия

- Лаба №3 (JWT): гость остаётся гостем — `X-Guest-Token` не меняется.
- Лаба №4: `AbuseSignals` → anti-doping-service; лимиты — внешняя политика.
- Полной защиты от накрутки нет (новая сессия = новый субъект) — это
  зафиксировано в требованиях (раздел 9, допущение 6).
```

В `docs/domain/adr/ADR-002-feed-own-projection.md` строку «Статус:» заменить на:

```markdown
Статус: принято (итерация 0), реализовано (итерация 8).
```

- [ ] **Step 2: Глоссарий, агрегаты, context map**

`docs/domain/glossary.md` — добавить строки в таблицу:

```markdown
| Гостевая сессия | `GuestSession` | tournaments | Анонимный субъект голосования глобальных окон; хранится только хэш токена |
| Токен гостя | `X-Guest-Token` | tournaments (заголовок) | Сырой токен активной сессии; USER при обоих заголовках всегда USER |
| Карточка ленты | `FeedCard` | feed | Строка проекции: участник открытого окна с публичными данными растения и владельца |
| Курсор ленты | `FeedCursor` | feed | Подписанный (HMAC) keyset-курсор: seed, snapshotCutoff, позиция, субъект |
| Seed ленты | `FeedOrdering.newSeed()` | feed | Псевдослучайный серверный seed порядка карточек (hashtextextended в SQL) |
```

`docs/domain/aggregates.md` — заполнить строку `GuestSession` (инварианты: «хранится только хэш токена; активна пока now < expiresAt; неизменяема после создания»; команда: `issue`; защищающий тест: `GuestSessionTest`) и добавить раздел feed-проекции (read-модель без агрегата: `FeedCard` обновляется событиями `VotingWindowOpened`/`VotingWindowClosed`, защищается `FeedCardRepositoryContractTest`/`FeedProjectionServiceTest`).

`docs/domain/context-map.md` — дополнить таблицу взаимодействий (инициатор feed): feed ← tournaments (события `VotingWindowOpened`/`VotingWindowClosed` — синхронно in-process, в лабе №4 outbox → Kafka → проекция; read `FeedDirectory`/`GuestSessionDirectory`), feed ← plants (`PlantDirectory`), feed ← identity (`UserDirectory`); отметить новые события в списке опубликованных.

- [ ] **Step 3: README, `.env.example`, docker-compose**

`README.md` — обновить разделы: «Демо-идентификация» (добавить `X-Guest-Token` и `POST /guest-sessions`), «Голосование» (убрать «гость — итерация 8», описать гостя: глобальные окна, 404 на закрытых, лимит 429), добавить раздел «Лента и гостевые сессии (feed)»:

```markdown
## Лента и гостевые сессии (feed)

- `POST /api/v1/guest-sessions` — публично; токен возвращается один раз
  (`{token, expiresAt}`), передаётся в заголовке `X-Guest-Token`.
  Лимит выдачи — 10/мин на IP (429 + `Retry-After`,
  `plantarena.guests.session-creation-limit-per-minute`).
- `GET /api/v1/feed?limit=20&cursor=...` — пользователь
  (`X-Demo-User-Id`) либо гость (`X-Guest-Token`; только глобальные
  карточки). Ответ `{items, nextCursor, hasNext}` **без total** —
  бесконечная прокрутка keyset-курсором (подписан HMAC,
  `plantarena.feed.cursor-secret`/`FEED_CURSOR_SECRET`).
  Невалидный курсор — 400 `FEED_CURSOR_INVALID`, истёкший (TTL
  `plantarena.feed.cursor-ttl`, по умолчанию 1h) — 410
  `FEED_CURSOR_EXPIRED` (начать новую ленту — без cursor).
- Карточки: активные открытые окна, живые растения, чужие entry, ещё не
  оценённые субъектом; псевдослучайный порядок от серверного seed
  (`hashtextextended` в SQL, ADR-002); новые участники появляются после
  обновления ленты (snapshotCutoff).
- Голос гостя: глобальные окна, дельты как у USER, лимит 30/мин на сессию
  (429); закрытые турниры скрыты (404). Идентифицированный пользователь
  при обоих заголовках — субъект USER.
```

`.env.example` — добавить:

```
FEED_CURSOR_SECRET=change-me-cursor-secret
```

`docker-compose.yml` — в `environment:` сервиса `app` добавить:

```yaml
      FEED_CURSOR_SECRET: ${FEED_CURSOR_SECRET:-dev-only-cursor-secret}
```

- [ ] **Step 4: Финальный verify и sanity docker**

```bash
./mvnw verify
docker compose up --build -d && sleep 30 && curl -fsS http://localhost:8080/actuator/health && docker compose down -v
```

Ожидание: verify зелёный (unit, application, контрактные, IT, ArchUnit, JaCoCo ≥ 70%); healthcheck отвечает.

- [ ] **Step 5: Commit и merge**

```bash
git add docs/domain/adr/ADR-013-guest-sessions.md \
  docs/domain/adr/ADR-002-feed-own-projection.md \
  docs/domain/glossary.md docs/domain/aggregates.md docs/domain/context-map.md \
  README.md .env.example docker-compose.yml
git commit -m "docs(feed): ADR-013 гостевые сессии, ADR-002 реализовано, глоссарий, агрегаты, context map, README"
git checkout main && git merge --no-ff feat/iteration-8-feed -m "merge: итерация 8 — лента и гостевые сессии"
```

(push — только по отдельной команде пользователя.)

---

## Самопроверка плана (выполнено автором)

- **Покрытие раздела 9:** GuestSession (Tasks 2–4), X-Guest-Token/USER-приоритет (Tasks 9–10), голос гостя только в глобальных окнах (Task 9), дельты/удаление (Task 1 IT), лента-смешение/чужие/неоценённые (Task 8 + IT), псевдослучайный порядок в SQL (Task 6), FeedCursor HMAC/400/410 (Tasks 6, 8, 10), snapshotCutoff (Task 8 + IT), лимиты 429 + Retry-After (Task 4), AbuseSignals (Task 4), `{items,nextCursor,hasNext}` без total (Task 10), повторная фильтрация прав/чужой курсор (Task 8 + IT).
- **Покрытие раздела 13:** `POST /guest-sessions` (публично, 201, 429), `GET /feed` (U либо guest token), limit 1–50 → 400, 404 скрытого PRIVATE для гостя, 401 без идентификации, operationId-префиксы `guest-`/`feed-`, X-Guest-Token в Swagger.
- **Типы согласованы:** `subjectKey` форматы `USER:`/`GUEST:` едины (домен, курсор, FeedDirectory); `FeedCard.sortKey` заполняется только в `page`; `FeedCardQuery` поля соответствуют SQL-запросам и фейку; сигнатура `VotingUseCase` с `guestToken` единообразно обновлена (сервис, контроллер, тесты).
- **Известные риски (проверить при выполнении):** нативный `IN (:коллекция)` требует непустых коллекций (sentinel в `JpaFeedCardRepository`); `hashtextextended` — детерминирована в рамках запроса (контракт — свойства, не значения); паттерн JPA-контрактных IT (`@DataJpaTest` vs `AbstractIntegrationTest`) — следовать существующим наследникам контекста tournaments.
