# Итерация 1 — identity: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Контекст identity: агрегат `User` (роли, профиль, координаты, passwordHash за портом `PasswordHasher`), bootstrap ADMIN из ENV, создание USER модератором, `CurrentActor` через `X-Demo-User-Id` (dev/test), `AccessPolicy`, REST `/users`, `/me`, роли, явная ошибка конфигурации без адаптера идентификации. DoD: сценарии раздела 2 требований проходят через HTTP на Testcontainers.

**Architecture:** `CurrentActor`/`CurrentActorProvider` — технический контракт в `shared.security`; провайдер реализует identity (демо-заголовок ADR-005, только профили dev/test; связывание — в `config`, обычный профиль без адаптера падает при старте). Внутри identity: `domain` (агрегат User, VO Email/GeoPoint, порты UserRepository/PasswordHasher) ← `application` (use cases, `IdentityAccessPolicy`, транзакции) ← `adapter` (`in.web` REST + демо-провайдер, `in.jobs` bootstrap, `out.persistence` JPA, `out.crypto` BCrypt). Исключения identity живут в `application` и переводятся в HTTP отдельным `@RestControllerAdvice` в `adapter.in.web` (shared не зависит от контекстов — правило 10.2.8).

**Tech Stack:** без новых технологий; добавляются зависимости `spring-security-crypto` (BCrypt за портом `PasswordHasher`) и test-артефакты Boot 4 `spring-boot-data-jpa-test`, `spring-boot-jdbc-test` (пакеты `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`, `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase` — проверены по артефактам 4.0.8).

## Global Constraints

- Все ограничения итерации 0 действуют: один Maven-модуль; версии закреплены; `domain` без Spring/JPA/Jackson; Enum → VARCHAR + CHECK, JPA `EnumType.STRING`; время из `Clock` (в identity время пока не используется — команды не принимают `now`); тесты на едином языке, `@DisplayName` на русском допустим.
- Пакеты только по правилу 10.2: ArchUnit-тесты итерации 0 должны оставаться зелёными после каждого task.
- `shared` не зависит от контекстов; `config` — единственное место, знающее несколько контекстов.
- Пароль: разрешён во входном DTO `POST /users`, хранится только хэш (BCrypt за портом `PasswordHasher`); никогда не присутствует в ответах, списках, логах и Entity-сериализации (ADR-005).
- Роли из `X-Demo-User-Id` не принимаются: пользователь и роли извлекаются из БД по ID (ADR-005).
- Пагинация (раздел 13): `page` от 0, `size` 1–50, по умолчанию 20; вне диапазона → 400; списки возвращают `X-Total-Count`.
- HTTP-коды (раздел 13): 201 + Location (создание), 200 (чтение/изменение), 204 (удаление), 400 (валидация/пагинация), 401 (нет/невалидная идентификация), 403 (нет права), 404 (не найден), 409 (дубликат email).
- Git: ветка `feat/iteration-1-identity`, Conventional Commits, красные тесты не в `main`, push — только по отдельной команде.
- `./mvnw verify` зелёный в конце каждого task; JaCoCo LINE ≥ 70%.
- Отложено по roadmap (не делать сейчас): 409 при деактивации с активными участи­ями (участия появятся в итерациях 5–7); фильтры списка пользователей (YAGNI, только page/size); `POST /guest-sessions` (контекст tournaments, итерация 8); `retryAt` (запреты картинок — итерация 3).

---

### Task 1: Красный приёмочный IT — сценарии раздела 2 через HTTP

**Files:**
- Modify: `src/test/java/com/plantarena/support/AbstractIntegrationTest.java`
- Test: `src/test/java/com/plantarena/identity/IdentityApiIT.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest` (MockMvc + Testcontainers) из итерации 0.
- Produces: все IT наследуют `AbstractIntegrationTest` с профилем `test` и bootstrap-админом `admin@plantarena.local` (свойства `plantarena.bootstrap-admin.email/password/display-name`); приёмочный IT `IdentityApiIT` — внешний цикл TDD, красный до Task 7.

- [ ] **Step 1: Создать ветку**

```bash
cd /Users/vovabag/Desktop/personal/plantBook/lab1-monolith
git checkout -b feat/iteration-1-identity
```

- [ ] **Step 2: Обновить `AbstractIntegrationTest` (профиль test + bootstrap ADMIN)**

Полное новое содержимое `src/test/java/com/plantarena/support/AbstractIntegrationTest.java`:

```java
package com.plantarena.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Базовый класс всех IT: Testcontainers PostgreSQL (singleton), профиль test
 * (демо-идентификация ADR-005) и bootstrap-админ из ENV (раздел 2 требований).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("plantarena.bootstrap-admin.email", () -> "admin@plantarena.local");
        registry.add("plantarena.bootstrap-admin.password", () -> "admin-password-123");
        registry.add("plantarena.bootstrap-admin.display-name", () -> "Bootstrap Admin");
    }
}
```

- [ ] **Step 3: Написать красный приёмочный IT**

`src/test/java/com/plantarena/identity/IdentityApiIT.java`:

```java
package com.plantarena.identity;

import com.jayway.jsonpath.JsonPath;
import com.plantarena.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Внешний цикл TDD итерации 1: сценарии раздела 2 требований
 * (роли и создание пользователей) через HTTP на Testcontainers.
 * Заголовок задаётся строковой константой, чтобы IT не зависел от реализации.
 */
@DisplayName("Сценарии раздела 2: роли и создание пользователей (identity)")
class IdentityApiIT extends AbstractIntegrationTest {

    private static final String DEMO_HEADER = "X-Demo-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void bootstrap_админ_существует_после_старта() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + adminId()).header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("admin@plantarena.local"))
            .andExpect(jsonPath("$.roles", containsInAnyOrder("USER", "ADMIN")))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void публичной_самостоятельной_регистрации_нет_гость_не_создаёт_пользователя() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"guest@example.com","password":"password-1","displayName":"Guest"}
                    """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("NOT_IDENTIFIED"));
    }

    @Test
    void админ_создаёт_пользователя_назначает_модератора_и_модератор_создаёт_пользователя() throws Exception {
        UUID futureModeratorId = createUserAsAdmin("alice@example.com", "Alice");
        grantModeratorAsAdmin(futureModeratorId);

        mockMvc.perform(post("/api/v1/users")
                .header(DEMO_HEADER, futureModeratorId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"bob@example.com","password":"password-2","displayName":"Bob"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.email").value("bob@example.com"))
            .andExpect(jsonPath("$.roles", containsInAnyOrder("USER")))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void обычный_пользователь_не_может_создавать_пользователей() throws Exception {
        UUID userId = createUserAsAdmin("plain@example.com", "Plain");

        mockMvc.perform(post("/api/v1/users")
                .header(DEMO_HEADER, userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"x@example.com","password":"password-3","displayName":"X"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void созданный_пользователь_всегда_только_user_роли_в_dto_передать_нельзя() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"hacker@example.com","password":"password-4","displayName":"Hacker",
                     "roles":["ADMIN"],"status":"DEACTIVATED"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.roles", containsInAnyOrder("USER")))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void patch_профиля_не_повышает_права() throws Exception {
        UUID userId = createUserAsAdmin("victim@example.com", "Victim");

        mockMvc.perform(patch("/api/v1/users/" + userId)
                .header(DEMO_HEADER, userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName":"New Name","roles":["ADMIN"],"status":"DEACTIVATED"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("New Name"))
            .andExpect(jsonPath("$.roles", containsInAnyOrder("USER")))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void пользователь_заполняет_профиль_и_координаты() throws Exception {
        UUID userId = createUserAsAdmin("geo@example.com", "Geo");

        mockMvc.perform(put("/api/v1/me/location")
                .header(DEMO_HEADER, userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"latitude":55.7558,"longitude":37.6173}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.latitude").value(55.7558))
            .andExpect(jsonPath("$.longitude").value(37.6173));

        mockMvc.perform(get("/api/v1/me").header(DEMO_HEADER, userId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Geo"))
            .andExpect(jsonPath("$.latitude").value(55.7558))
            .andExpect(jsonPath("$.longitude").value(37.6173));
    }

    @Test
    void гость_не_видит_собственный_профиль() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("NOT_IDENTIFIED"));
    }

    @Test
    void неизвестный_demo_id_отклоняется() throws Exception {
        mockMvc.perform(get("/api/v1/me").header(DEMO_HEADER, UUID.randomUUID().toString()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("NOT_IDENTIFIED"));
    }

    @Test
    void некорректный_demo_id_отклоняется() throws Exception {
        mockMvc.perform(get("/api/v1/me").header(DEMO_HEADER, "not-a-uuid"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("NOT_IDENTIFIED"));
    }

    @Test
    void деактивированный_пользователь_не_проходит_идентификацию() throws Exception {
        UUID userId = createUserAsAdmin("gone@example.com", "Gone");

        mockMvc.perform(delete("/api/v1/users/" + userId).header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me").header(DEMO_HEADER, userId.toString()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("NOT_IDENTIFIED"));
    }

    @Test
    void только_админ_управляет_ролями() throws Exception {
        UUID moderatorId = createUserAsAdmin("mod@example.com", "Mod");
        grantModeratorAsAdmin(moderatorId);
        UUID otherId = createUserAsAdmin("other@example.com", "Other");

        mockMvc.perform(put("/api/v1/users/" + otherId + "/roles/moderator")
                .header(DEMO_HEADER, moderatorId.toString()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void назначение_и_снятие_модератора_идемпотентны() throws Exception {
        UUID userId = createUserAsAdmin("idem@example.com", "Idem");

        mockMvc.perform(put("/api/v1/users/" + userId + "/roles/moderator")
                .header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles", containsInAnyOrder("USER", "MODERATOR")));

        mockMvc.perform(put("/api/v1/users/" + userId + "/roles/moderator")
                .header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles", containsInAnyOrder("USER", "MODERATOR")));

        mockMvc.perform(delete("/api/v1/users/" + userId + "/roles/moderator")
                .header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles", containsInAnyOrder("USER")));

        mockMvc.perform(delete("/api/v1/users/" + userId + "/roles/moderator")
                .header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles", containsInAnyOrder("USER")));
    }

    @Test
    void обычный_пользователь_не_видит_чужой_профиль() throws Exception {
        UUID userId = createUserAsAdmin("owner@example.com", "Owner");
        UUID strangerId = createUserAsAdmin("stranger@example.com", "Stranger");

        mockMvc.perform(get("/api/v1/users/" + userId).header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void список_пользователей_отдаёт_X_Total_Count_и_отклоняет_пагинацию_вне_диапазона() throws Exception {
        createUserAsAdmin("list@example.com", "List");

        MvcResult result = mockMvc.perform(get("/api/v1/users").header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Total-Count"))
            .andReturn();
        long total = Long.parseLong(result.getResponse().getHeader("X-Total-Count"));
        assertThat(total).isGreaterThanOrEqualTo(2);

        mockMvc.perform(get("/api/v1/users")
                .header(DEMO_HEADER, adminId().toString()).param("size", "51"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));

        mockMvc.perform(get("/api/v1/users")
                .header(DEMO_HEADER, adminId().toString()).param("page", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
    }

    @Test
    void email_должен_быть_уникальным() throws Exception {
        createUserAsAdmin("dup@example.com", "Dup");

        mockMvc.perform(post("/api/v1/users")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"dup@example.com","password":"password-5","displayName":"Dup 2"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_IN_USE"));
    }

    @Test
    void некорректные_поля_создания_отклоняются() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                .header(DEMO_HEADER, adminId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"not-an-email","password":"short","displayName":""}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors").isArray())
            .andExpect(jsonPath("$.fieldErrors.length()").value(3));
    }

    @Test
    void пароль_и_хэш_не_появляются_в_ответах() throws Exception {
        UUID userId = createUserAsAdmin("secret@example.com", "Secret");

        String profile = mockMvc.perform(get("/api/v1/users/" + userId)
                .header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(profile).doesNotContain("password");

        String me = mockMvc.perform(get("/api/v1/me").header(DEMO_HEADER, userId.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(me).doesNotContain("password");
    }

    private UUID adminId() {
        return userIdByEmail("admin@plantarena.local");
    }

    private UUID userIdByEmail(String email) {
        return jdbcTemplate.queryForObject(
            "select id from identity.app_user where email_normalized = ?", UUID.class, email);
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

    private void grantModeratorAsAdmin(UUID userId) throws Exception {
        mockMvc.perform(put("/api/v1/users/" + userId + "/roles/moderator")
                .header(DEMO_HEADER, adminId().toString()))
            .andExpect(status().isOk());
    }
}
```

- [ ] **Step 4: Запустить и убедиться, что новый IT красный, а остальное зелёное**

```bash
./mvnw -q -Dit.name=IdentityApiIT verify
```

Expected: FAIL — все тесты `IdentityApiIT` падают (эндпоинты `/api/v1/users`, `/api/v1/me` ещё не существуют → 404; `identity.app_user` ещё нет → `adminId()` падает на отсутствии таблицы). Остальные IT итерации 0 остаются зелёными (профиль `test` и новые свойства ничего не ломают).

- [ ] **Step 5: Полный verify (только для контроля, что итерация 0 не сломана)**

```bash
./mvnw -q verify
```

Expected: FAIL только в `IdentityApiIT`; surefire и остальные IT зелёные.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/plantarena/support/AbstractIntegrationTest.java src/test/java/com/plantarena/identity/IdentityApiIT.java
git commit -m "test: красный приёмочный IT identity (сценарии раздела 2 требований)"
```

---

### Task 2: shared.security (CurrentActor) и пагинация shared.web

**Files:**
- Create: `src/main/java/com/plantarena/shared/security/AppRole.java`
- Create: `src/main/java/com/plantarena/shared/security/CurrentActor.java`
- Create: `src/main/java/com/plantarena/shared/security/CurrentActorProvider.java`
- Create: `src/main/java/com/plantarena/shared/security/NotIdentifiedException.java`
- Create: `src/main/java/com/plantarena/shared/security/AccessDeniedException.java`
- Create: `src/main/java/com/plantarena/shared/web/PaginationParams.java`
- Create: `src/main/java/com/plantarena/shared/web/InvalidPaginationException.java`
- Modify: `src/main/java/com/plantarena/shared/web/ApiExceptionHandler.java`
- Test: `src/test/java/com/plantarena/shared/web/PaginationParamsTest.java`
- Test: `src/test/java/com/plantarena/shared/web/ApiExceptionHandlerTest.java`

**Interfaces:**
- Produces: `AppRole` (enum USER/MODERATOR/ADMIN); `CurrentActor` (record: `UUID userId`, `Set<AppRole> roles`, `boolean isGuest`; фабрики `guest()`/`identified(UUID, Set<AppRole>)`, метод `hasRole(AppRole)`, аксессор `isGuest()` — компонент переименован с `guest`: аксессор записи `guest()` конфликтует по сигнатуре со статической фабрикой `guest()`); `CurrentActorProvider` (интерфейс, метод `CurrentActor currentActor()`); `NotIdentifiedException` → HTTP 401 `NOT_IDENTIFIED`; `AccessDeniedException` → HTTP 403 `ACCESS_DENIED`; `PaginationParams.of(Integer page, Integer size)` (default 0/20, диапазон page ≥ 0, size 1–50, иначе `InvalidPaginationException` → HTTP 400 `INVALID_PAGINATION`), метод `offset()`. Используются Task 4, 6, 7.

- [ ] **Step 1: Написать падающие тесты**

`src/test/java/com/plantarena/shared/web/PaginationParamsTest.java`:

```java
package com.plantarena.shared.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Параметры пагинации списков (раздел 13)")
class PaginationParamsTest {

    @Test
    void значения_по_умолчанию_page_0_size_20() {
        PaginationParams params = PaginationParams.of(null, null);

        assertThat(params.page()).isZero();
        assertThat(params.size()).isEqualTo(20);
        assertThat(params.offset()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,51", "0,-1"})
    void параметры_вне_диапазона_отклоняются(int page, int size) {
        assertThatThrownBy(() -> PaginationParams.of(page, size))
            .isInstanceOf(InvalidPaginationException.class);
    }

    @Test
    void верхняя_граница_размера_допустима() {
        assertThat(PaginationParams.of(3, 50).offset()).isEqualTo(150);
    }
}
```

`src/test/java/com/plantarena/shared/web/ApiExceptionHandlerTest.java`:

```java
package com.plantarena.shared.web;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.NotIdentifiedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Общие ошибки API: идентификация, доступ, пагинация")
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");

    @Test
    void неидентифицированный_субъект_получает_401_not_identified() {
        ResponseEntity<ApiError> response =
            handler.notIdentified(new NotIdentifiedException("Требуется идентификация"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("NOT_IDENTIFIED");
        assertThat(response.getBody().detail()).isEqualTo("Требуется идентификация");
    }

    @Test
    void отказ_в_доступе_даёт_403_access_denied() {
        ResponseEntity<ApiError> response =
            handler.accessDenied(new AccessDeniedException("Только админ"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().code()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void некорректная_пагинация_даёт_400_invalid_pagination() {
        ResponseEntity<ApiError> response =
            handler.invalidPagination(new InvalidPaginationException("size 51 вне диапазона"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("INVALID_PAGINATION");
    }
}
```

- [ ] **Step 2: Запустить и убедиться в падении (компиляция)**

```bash
./mvnw -q -Dtest='PaginationParamsTest,ApiExceptionHandlerTest' test
```

Expected: COMPILATION ERROR — классы `PaginationParams`, `InvalidPaginationException`, `NotIdentifiedException`, `AccessDeniedException` и методы обработчика не существуют.

- [ ] **Step 3: Реализовать `shared.security`**

`src/main/java/com/plantarena/shared/security/AppRole.java`:

```java
package com.plantarena.shared.security;

/**
 * Технический код роли для CurrentActor (раздел 2 требований).
 * Доменная роль identity (identity.domain.UserRole) маппится в него адаптером.
 */
public enum AppRole {
    USER, MODERATOR, ADMIN
}
```

`src/main/java/com/plantarena/shared/security/CurrentActor.java`:

```java
package com.plantarena.shared.security;

import java.util.Set;
import java.util.UUID;

/**
 * Технический тип текущего субъекта (раздел 2): userId, роли, признак гостя.
 * Живёт в shared.security; провайдера реализует контекст identity.
 */
public record CurrentActor(UUID userId, Set<AppRole> roles, boolean isGuest) {

    public CurrentActor {
        roles = Set.copyOf(roles);
    }

    public static CurrentActor guest() {
        return new CurrentActor(null, Set.of(), true);
    }

    public static CurrentActor identified(UUID userId, Set<AppRole> roles) {
        return new CurrentActor(userId, roles, false);
    }

    public boolean hasRole(AppRole role) {
        return roles.contains(role);
    }
}
```

`src/main/java/com/plantarena/shared/security/CurrentActorProvider.java`:

```java
package com.plantarena.shared.security;

/**
 * Провайдер текущего субъекта. Реализует контекст identity:
 * в лабе №1 — демо-заголовок X-Demo-User-Id (ADR-005),
 * в лабе №3 заменяется на Spring Security + JWT без изменения потребителей.
 */
public interface CurrentActorProvider {

    CurrentActor currentActor();
}
```

`src/main/java/com/plantarena/shared/security/NotIdentifiedException.java`:

```java
package com.plantarena.shared.security;

/**
 * Идентификация отсутствует или невалидна для защищённой ручки (HTTP 401).
 * Техническое исключение, не доменное.
 */
public class NotIdentifiedException extends RuntimeException {

    public NotIdentifiedException(String message) {
        super(message);
    }
}
```

`src/main/java/com/plantarena/shared/security/AccessDeniedException.java`:

```java
package com.plantarena.shared.security;

/**
 * Идентифицированный субъект не имеет права на действие (HTTP 403).
 * Техническое исключение, не доменное.
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Реализовать пагинацию `shared.web`**

`src/main/java/com/plantarena/shared/web/PaginationParams.java`:

```java
package com.plantarena.shared.web;

/**
 * Параметры пагинации списков (раздел 13): page от 0, size 1–50, по умолчанию 20.
 */
public record PaginationParams(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    public static PaginationParams of(Integer page, Integer size) {
        int resolvedPage = page == null ? 0 : page;
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedPage < 0 || resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw new InvalidPaginationException(
                "Параметры пагинации вне диапазона: page=" + resolvedPage + ", size=" + resolvedSize
                    + " (допустимо: page >= 0, size 1–" + MAX_SIZE + ")");
        }
        return new PaginationParams(resolvedPage, resolvedSize);
    }

    public int offset() {
        return page * size;
    }
}
```

`src/main/java/com/plantarena/shared/web/InvalidPaginationException.java`:

```java
package com.plantarena.shared.web;

/**
 * Параметры пагинации вне допустимого диапазона (HTTP 400).
 */
public class InvalidPaginationException extends RuntimeException {

    public InvalidPaginationException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: Дополнить `ApiExceptionHandler` (полное новое содержимое)**

`src/main/java/com/plantarena/shared/web/ApiExceptionHandler.java`:

```java
package com.plantarena.shared.web;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.NotIdentifiedException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Переводит технические исключения (shared) в ProblemDetail-подобное тело ApiError.
 * Доменные исключения контекстов переводятся их собственными advice-классами
 * в adapter.in.web — shared не зависит от контекстов (правило 10.2.8).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> notFound(NoResourceFoundException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
            "Ресурс не найден: " + request.getRequestURI(), request);
    }

    @ExceptionHandler(NotIdentifiedException.class)
    public ResponseEntity<ApiError> notIdentified(NotIdentifiedException e, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, "NOT_IDENTIFIED", e.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> accessDenied(AccessDeniedException e, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "ACCESS_DENIED", e.getMessage(), request);
    }

    @ExceptionHandler(InvalidPaginationException.class)
    public ResponseEntity<ApiError> invalidPagination(InvalidPaginationException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", e.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_BODY",
            "Некорректное тело запроса", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
            .map(fieldError -> new ApiError.FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
            .toList();
        ApiError error = error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
            "Некорректные поля запроса", request).withFieldErrors(fieldErrors);
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "Внутренняя ошибка сервера", request);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String detail,
                                              HttpServletRequest request) {
        return ResponseEntity.status(status)
            .body(error(status, code, detail, request));
    }

    private ApiError error(HttpStatus status, String code, String detail, HttpServletRequest request) {
        String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return new ApiError(
            URI.create("about:blank"),
            status.getReasonPhrase(),
            status.value(),
            detail,
            URI.create(request.getRequestURI()),
            code,
            List.of(),
            traceId);
    }
}
```

- [ ] **Step 6: Запустить и убедиться в прохождении**

```bash
./mvnw -q -Dtest='PaginationParamsTest,ApiExceptionHandlerTest' test
```

Expected: PASS.

- [ ] **Step 7: Полный verify (ArchUnit + все тесты)**

```bash
./mvnw -q verify
```

Expected: FAIL только в `IdentityApiIT` (Task 1); ArchUnit зелёный (shared не зависит от контекстов).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/plantarena/shared/ src/test/java/com/plantarena/shared/web/
git commit -m "feat: shared.security (CurrentActor) и пагинация shared.web с кодами ошибок"
```

---

### Task 3: Домен identity — агрегат User, VO, порты

**Files:**
- Create: `src/main/java/com/plantarena/identity/domain/UserRole.java`
- Create: `src/main/java/com/plantarena/identity/domain/UserStatus.java`
- Create: `src/main/java/com/plantarena/identity/domain/Email.java`
- Create: `src/main/java/com/plantarena/identity/domain/GeoPoint.java`
- Create: `src/main/java/com/plantarena/identity/domain/User.java`
- Create: `src/main/java/com/plantarena/identity/domain/PasswordHasher.java`
- Create: `src/main/java/com/plantarena/identity/domain/UserRepository.java`
- Test: `src/test/java/com/plantarena/identity/domain/UserTest.java`
- Test: `src/test/java/com/plantarena/identity/domain/EmailTest.java`
- Test: `src/test/java/com/plantarena/identity/domain/GeoPointTest.java`

**Interfaces:**
- Produces: `UserRole` (enum USER/MODERATOR/ADMIN), `UserStatus` (enum ACTIVE/DEACTIVATED); VO `Email(String value)` (нормализация к нижнему регистру + проверка формата в конструкторе), `GeoPoint(double latitude, double longitude)` (широта [-90, 90], долгота [-180, 180], NaN/Infinity отклоняются); агрегат `User`: фабрики `registerUser(Email, String displayName, String passwordHash)`, `bootstrapAdmin(Email, String, String)`, `restore(UUID, Email, String, String, Set<UserRole>, UserStatus, GeoPoint, long version)` (только для persistence); команды `grantModerator()`, `revokeModerator()`, `changeDisplayName(String)`, `moveTo(GeoPoint)`, `deactivate()`; геттеры `id()`, `email()`, `displayName()`, `passwordHash()`, `roles()` (неизменяемая копия), `status()`, `location()`, `version()`. Порты: `PasswordHasher` (`hash(String)`, `matches(String, String)`), `UserRepository` (`save`, `findById(UUID)`, `findByEmail(Email)`, `findAll(int offset, int limit)`, `count()`). Используются Task 4, 5, 6.

- [ ] **Step 1: Написать падающие доменные тесты**

`src/test/java/com/plantarena/identity/domain/UserTest.java`:

```java
package com.plantarena.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Агрегат User: инварианты identity (aggregates.md)")
class UserTest {

    @Test
    void у_созданного_пользователя_всегда_есть_роль_user() {
        User user = User.registerUser(new Email("alice@example.com"), "Alice", "hash-1");

        assertThat(user.roles()).containsExactly(UserRole.USER);
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void у_bootstrap_админа_роли_user_и_admin() {
        User admin = User.bootstrapAdmin(new Email("admin@example.com"), "Admin", "hash-2");

        assertThat(admin.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.ADMIN);
    }

    @Test
    void роли_меняются_только_отдельными_командами_назначить_и_снять_модератора() {
        User user = User.registerUser(new Email("bob@example.com"), "Bob", "hash-3");

        user.grantModerator();
        assertThat(user.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.MODERATOR);

        user.revokeModerator();
        assertThat(user.roles()).containsExactly(UserRole.USER);
    }

    @Test
    void снятие_модератора_не_удаляет_роль_user() {
        User user = User.registerUser(new Email("carol@example.com"), "Carol", "hash-4");

        user.revokeModerator();

        assertThat(user.roles()).containsExactly(UserRole.USER);
    }

    @Test
    void пустое_имя_профиля_отклоняется() {
        User user = User.registerUser(new Email("dave@example.com"), "Dave", "hash-5");

        assertThatThrownBy(() -> user.changeDisplayName("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void деактивация_переводит_учётную_запись_в_deactivated() {
        User user = User.registerUser(new Email("eve@example.com"), "Eve", "hash-6");

        user.deactivate();

        assertThat(user.status()).isEqualTo(UserStatus.DEACTIVATED);
    }

    @Test
    void restore_восстанавливает_все_поля_включая_версию() {
        User restored = User.restore(
            java.util.UUID.randomUUID(), new Email("restored@example.com"), "Restored", "hash-7",
            java.util.Set.of(UserRole.USER, UserRole.MODERATOR), UserStatus.DEACTIVATED,
            new GeoPoint(10, 20), 42);

        assertThat(restored.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.MODERATOR);
        assertThat(restored.status()).isEqualTo(UserStatus.DEACTIVATED);
        assertThat(restored.location()).isEqualTo(new GeoPoint(10, 20));
        assertThat(restored.version()).isEqualTo(42);
    }
}
```

`src/test/java/com/plantarena/identity/domain/EmailTest.java`:

```java
package com.plantarena.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VO Email: нормализация и формат")
class EmailTest {

    @Test
    void email_нормализуется_к_нижнему_регистру_и_без_пробелов() {
        assertThat(new Email("  Alice@Example.COM ").value()).isEqualTo("alice@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "no-at-sign", "a@b", "a b@example.com", "a@@example.com"})
    void некорректный_email_невозможно_создать(String value) {
        assertThatThrownBy(() -> new Email(value))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`src/test/java/com/plantarena/identity/domain/GeoPointTest.java`:

```java
package com.plantarena.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VO GeoPoint: допустимые диапазоны координат")
class GeoPointTest {

    @ParameterizedTest
    @ValueSource(doubles = {-90.0001, 90.0001, Double.NaN, Double.POSITIVE_INFINITY})
    void широта_вне_диапазона_отклоняется(double latitude) {
        assertThatThrownBy(() -> new GeoPoint(latitude, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-180.0001, 180.0001, Double.NaN})
    void долгота_вне_диапазона_отклоняется(double longitude) {
        assertThatThrownBy(() -> new GeoPoint(0, longitude))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void граничные_координаты_допустимы() {
        assertThat(new GeoPoint(-90, -180)).isNotNull();
        assertThat(new GeoPoint(90, 180)).isNotNull();
    }
}
```

- [ ] **Step 2: Запустить и убедиться в падении (компиляция)**

```bash
./mvnw -q -Dtest='UserTest,EmailTest,GeoPointTest' test
```

Expected: COMPILATION ERROR — классы домена не существуют.

- [ ] **Step 3: Реализовать enum'ы и VO**

`src/main/java/com/plantarena/identity/domain/UserRole.java`:

```java
package com.plantarena.identity.domain;

/**
 * Роль учётной записи (раздел 2). USER присутствует всегда (инвариант агрегата User).
 */
public enum UserRole {
    USER, MODERATOR, ADMIN
}
```

`src/main/java/com/plantarena/identity/domain/UserStatus.java`:

```java
package com.plantarena.identity.domain;

/**
 * Статус учётной записи. Деактивация необратима (обратной команды нет).
 */
public enum UserStatus {
    ACTIVE, DEACTIVATED
}
```

`src/main/java/com/plantarena/identity/domain/Email.java`:

```java
package com.plantarena.identity.domain;

import java.util.Locale;

/**
 * VO «Email»: нормализуется к нижнему регистру без краёв, формат проверяется
 * в конструкторе — некорректный объект невозможно создать.
 */
public record Email(String value) {

    private static final String PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    public Email {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches(PATTERN)) {
            throw new IllegalArgumentException("Некорректный email: " + value);
        }
        value = normalized;
    }
}
```

`src/main/java/com/plantarena/identity/domain/GeoPoint.java`:

```java
package com.plantarena.identity.domain;

/**
 * VO «Координаты»: широта [-90, 90], долгота [-180, 180], границы включаются.
 */
public record GeoPoint(double latitude, double longitude) {

    public GeoPoint {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Широта вне диапазона [-90, 90]: " + latitude);
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Долгота вне диапазона [-180, 180]: " + longitude);
        }
    }
}
```

- [ ] **Step 4: Реализовать агрегат `User`**

`src/main/java/com/plantarena/identity/domain/User.java`:

```java
package com.plantarena.identity.domain;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Агрегат «Пользователь» (identity): роли, профиль, координаты, хэш пароля, статус.
 * Инварианты (aggregates.md): роль USER присутствует всегда; роли меняются только
 * отдельными командами; координаты в допустимых диапазонах (GeoPoint).
 */
public class User {

    private final UUID id;
    private final Email email;
    private String displayName;
    private final String passwordHash;
    private final Set<UserRole> roles = EnumSet.noneOf(UserRole.class);
    private UserStatus status;
    private GeoPoint location;
    private final long version;

    private User(UUID id, Email email, String displayName, String passwordHash,
                 Set<UserRole> roles, UserStatus status, GeoPoint location, long version) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.roles.addAll(roles);
        this.roles.add(UserRole.USER);
        this.status = status;
        this.location = location;
        this.version = version;
    }

    /** Создание обычного пользователя (создаёт модератор/админ): роль USER, ACTIVE. */
    public static User registerUser(Email email, String displayName, String passwordHash) {
        return new User(UUID.randomUUID(), email, displayName, passwordHash,
            EnumSet.of(UserRole.USER), UserStatus.ACTIVE, null, 0);
    }

    /** Bootstrap-админ из ENV (раздел 2 требований). */
    public static User bootstrapAdmin(Email email, String displayName, String passwordHash) {
        return new User(UUID.randomUUID(), email, displayName, passwordHash,
            EnumSet.of(UserRole.USER, UserRole.ADMIN), UserStatus.ACTIVE, null, 0);
    }

    /** Восстановление из хранилища (использует только persistence-адаптер). */
    public static User restore(UUID id, Email email, String displayName, String passwordHash,
                               Set<UserRole> roles, UserStatus status, GeoPoint location, long version) {
        return new User(id, email, displayName, passwordHash, roles, status, location, version);
    }

    /** Назначить роль MODERATOR (идемпотентно). */
    public void grantModerator() {
        roles.add(UserRole.MODERATOR);
    }

    /** Снять роль MODERATOR, не удаляя USER (идемпотентно). */
    public void revokeModerator() {
        roles.remove(UserRole.MODERATOR);
    }

    public void changeDisplayName(String newDisplayName) {
        if (newDisplayName == null || newDisplayName.isBlank()) {
            throw new IllegalArgumentException("Имя профиля не может быть пустым");
        }
        this.displayName = newDisplayName.trim();
    }

    public void moveTo(GeoPoint newLocation) {
        this.location = newLocation;
    }

    /** Деактивация учётной записи; обратной команды нет. */
    public void deactivate() {
        this.status = UserStatus.DEACTIVATED;
    }

    public UUID id() {
        return id;
    }

    public Email email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public Set<UserRole> roles() {
        return Set.copyOf(roles);
    }

    public UserStatus status() {
        return status;
    }

    public GeoPoint location() {
        return location;
    }

    public long version() {
        return version;
    }
}
```

- [ ] **Step 5: Реализовать порты**

`src/main/java/com/plantarena/identity/domain/PasswordHasher.java`:

```java
package com.plantarena.identity.domain;

/**
 * Порт хэширования паролей: домен не знает о spring-security-crypto (раздел 2).
 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}
```

`src/main/java/com/plantarena/identity/domain/UserRepository.java`:

```java
package com.plantarena.identity.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Порт репозитория агрегата User: интерфейс в domain,
 * реализация в adapter.out.persistence (раздел 5 требований).
 * offset всегда выровнен по size (page * size, PaginationParams).
 */
public interface UserRepository {

    User save(User user);

    Optional<User> findById(UUID id);

    Optional<User> findByEmail(Email email);

    List<User> findAll(int offset, int limit);

    long count();
}
```

- [ ] **Step 6: Запустить и убедиться в прохождении**

```bash
./mvnw -q -Dtest='UserTest,EmailTest,GeoPointTest' test
```

Expected: PASS.

- [ ] **Step 7: Полный verify (ArchUnit: домен без Spring)**

```bash
./mvnw -q verify
```

Expected: FAIL только в `IdentityApiIT`; ArchUnit зелёный.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/plantarena/identity/domain/ src/test/java/com/plantarena/identity/domain/
git commit -m "feat: домен identity — агрегат User, VO Email/GeoPoint, порты UserRepository и PasswordHasher"
```

---

### Task 4: Application-слой identity — use cases, AccessPolicy, фейки

**Files:**
- Create: `src/main/java/com/plantarena/identity/application/UserNotFoundException.java`
- Create: `src/main/java/com/plantarena/identity/application/EmailAlreadyInUseException.java`
- Create: `src/main/java/com/plantarena/identity/application/UserResult.java`
- Create: `src/main/java/com/plantarena/identity/application/IdentityAccessPolicy.java`
- Create: `src/main/java/com/plantarena/identity/application/port/in/UserAdministrationUseCase.java`
- Create: `src/main/java/com/plantarena/identity/application/port/in/MyProfileUseCase.java`
- Create: `src/main/java/com/plantarena/identity/application/port/in/ResolveActorUseCase.java`
- Create: `src/main/java/com/plantarena/identity/application/port/in/BootstrapAdminUseCase.java`
- Create: `src/main/java/com/plantarena/identity/application/UserAdministrationService.java`
- Create: `src/main/java/com/plantarena/identity/application/MyProfileService.java`
- Create: `src/main/java/com/plantarena/identity/application/ResolveActorService.java`
- Create: `src/main/java/com/plantarena/identity/application/BootstrapAdminService.java`
- Test: `src/test/java/com/plantarena/identity/application/support/InMemoryUserRepository.java`
- Test: `src/test/java/com/plantarena/identity/application/support/FakePasswordHasher.java`
- Test: `src/test/java/com/plantarena/identity/application/UserAdministrationServiceTest.java`
- Test: `src/test/java/com/plantarena/identity/application/MyProfileServiceTest.java`
- Test: `src/test/java/com/plantarena/identity/application/ResolveActorServiceTest.java`
- Test: `src/test/java/com/plantarena/identity/application/BootstrapAdminServiceTest.java`

**Interfaces:**
- Consumes: домен Task 3, `CurrentActor`/`AppRole`/`NotIdentifiedException`/`AccessDeniedException` Task 2.
- Produces: входные порты (используются Task 6, 7):
  - `UserAdministrationUseCase`: `create(CurrentActor, CreateUserCommand(String email, String password, String displayName))`, `get(CurrentActor, UUID)`, `list(CurrentActor, int page, int size)` → `UserListResult(List<UserResult> items, long total)`, `updateDisplayName(CurrentActor, UUID, String)`, `deactivate(CurrentActor, UUID)`, `grantModerator(CurrentActor, UUID)`, `revokeModerator(CurrentActor, UUID)`;
  - `MyProfileUseCase`: `me(CurrentActor)`, `updateLocation(CurrentActor, double latitude, double longitude)`;
  - `ResolveActorUseCase`: `resolve(UUID userId)` → `CurrentActor` (неизвестный/деактивированный → `NotIdentifiedException`);
  - `BootstrapAdminUseCase`: `ensureAdmin(String email, String password, String displayName)` (идемпотентно).
  - `UserResult(UUID id, String email, String displayName, Set<UserRole> roles, UserStatus status, Double latitude, Double longitude)` — без passwordHash.
  - Исключения: `UserNotFoundException` (→ 404 Task 7), `EmailAlreadyInUseException` (→ 409 Task 7).
  - Тестовые фейки `InMemoryUserRepository`, `FakePasswordHasher` — используются также Task 5 (контрактный тест).

- [ ] **Step 1: Написать тестовые фейки**

`src/test/java/com/plantarena/identity/application/support/InMemoryUserRepository.java`:

```java
package com.plantarena.identity.application.support;

import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fake репозитория User для application-тестов (раздел 14.2).
 * Честность фейка проверяется контрактным тестом Task 5.
 */
public class InMemoryUserRepository implements UserRepository {

    private final Map<UUID, User> usersById = new ConcurrentHashMap<>();

    @Override
    public User save(User user) {
        usersById.put(user.id(), user);
        return user;
    }

    @Override
    public Optional<User> findById(UUID id) {
        return Optional.ofNullable(usersById.get(id));
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return usersById.values().stream()
            .filter(user -> user.email().equals(email))
            .findFirst();
    }

    @Override
    public List<User> findAll(int offset, int limit) {
        List<User> all = new ArrayList<>(usersById.values());
        all.sort(Comparator.comparing(user -> user.id().toString()));
        return all.subList(Math.min(offset, all.size()), Math.min(offset + limit, all.size()));
    }

    @Override
    public long count() {
        return usersById.size();
    }
}
```

`src/test/java/com/plantarena/identity/application/support/FakePasswordHasher.java`:

```java
package com.plantarena.identity.application.support;

import com.plantarena.identity.domain.PasswordHasher;

/**
 * Детерминированный fake хэширования паролей — только для тестов.
 */
public class FakePasswordHasher implements PasswordHasher {

    @Override
    public String hash(String rawPassword) {
        return "fake:" + rawPassword;
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return passwordHash.equals("fake:" + rawPassword);
    }
}
```

- [ ] **Step 2: Написать падающие application-тесты**

`src/test/java/com/plantarena/identity/application/UserAdministrationServiceTest.java`:

```java
package com.plantarena.identity.application;

import com.plantarena.identity.application.port.in.UserAdministrationUseCase;
import com.plantarena.identity.application.support.FakePasswordHasher;
import com.plantarena.identity.application.support.InMemoryUserRepository;
import com.plantarena.identity.domain.UserRole;
import com.plantarena.identity.domain.UserStatus;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Use case: служебное управление пользователями (раздел 2)")
class UserAdministrationServiceTest {

    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final UserAdministrationService service =
        new UserAdministrationService(users, new FakePasswordHasher(), new IdentityAccessPolicy());

    private final CurrentActor admin =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER, AppRole.ADMIN));
    private final CurrentActor moderator =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER, AppRole.MODERATOR));
    private final CurrentActor plainUser =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER));

    @Test
    void модератор_создаёт_обычного_пользователя() {
        UserResult created = service.create(moderator,
            new UserAdministrationUseCase.CreateUserCommand("alice@example.com", "password-1", "Alice"));

        assertThat(created.email()).isEqualTo("alice@example.com");
        assertThat(created.roles()).containsExactly(UserRole.USER);
        assertThat(created.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void гостю_запрещено_создавать_пользователей() {
        assertThatThrownBy(() -> service.create(CurrentActor.guest(), cmd("guest@example.com")))
            .isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    void обычному_пользователю_запрещено_создавать_пользователей() {
        assertThatThrownBy(() -> service.create(plainUser, cmd("plain@example.com")))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void занятый_email_отклоняется() {
        service.create(moderator, cmd("dup@example.com"));

        assertThatThrownBy(() -> service.create(admin, cmd("dup@example.com")))
            .isInstanceOf(EmailAlreadyInUseException.class);
    }

    @Test
    void только_админ_назначает_и_снимает_модератора() {
        UserResult created = service.create(admin, cmd("mod@example.com"));

        assertThatThrownBy(() -> service.grantModerator(moderator, created.id()))
            .isInstanceOf(AccessDeniedException.class);

        UserResult granted = service.grantModerator(admin, created.id());
        assertThat(granted.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.MODERATOR);

        UserResult revoked = service.revokeModerator(admin, created.id());
        assertThat(revoked.roles()).containsExactly(UserRole.USER);
    }

    @Test
    void назначение_модератора_идемпотентно() {
        UserResult created = service.create(admin, cmd("idem@example.com"));

        service.grantModerator(admin, created.id());
        UserResult again = service.grantModerator(admin, created.id());

        assertThat(again.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.MODERATOR);
    }

    @Test
    void профиль_меняет_владелец_или_админ_но_не_другой_пользователь() {
        UserResult created = service.create(admin, cmd("victim@example.com"));

        assertThatThrownBy(() -> service.updateDisplayName(plainUser, created.id(), "Hacker"))
            .isInstanceOf(AccessDeniedException.class);

        assertThat(service.updateDisplayName(admin, created.id(), "New Name").displayName())
            .isEqualTo("New Name");
    }

    @Test
    void деактивирует_только_админ() {
        UserResult created = service.create(admin, cmd("gone@example.com"));

        assertThatThrownBy(() -> service.deactivate(moderator, created.id()))
            .isInstanceOf(AccessDeniedException.class);

        service.deactivate(admin, created.id());

        assertThat(users.findById(created.id()).orElseThrow().status())
            .isEqualTo(UserStatus.DEACTIVATED);
    }

    @Test
    void несуществующий_пользователь_не_найден() {
        assertThatThrownBy(() -> service.get(admin, UUID.randomUUID()))
            .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void список_возвращает_всех_с_количеством() {
        service.create(admin, cmd("u1@example.com"));
        service.create(admin, cmd("u2@example.com"));

        UserAdministrationUseCase.UserListResult page = service.list(moderator, 0, 20);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(2);
    }

    private UserAdministrationUseCase.CreateUserCommand cmd(String email) {
        return new UserAdministrationUseCase.CreateUserCommand(email, "password-1", "Name");
    }
}
```

`src/test/java/com/plantarena/identity/application/MyProfileServiceTest.java`:

```java
package com.plantarena.identity.application;

import com.plantarena.identity.application.support.InMemoryUserRepository;
import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRole;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Use case: собственный профиль (/me)")
class MyProfileServiceTest {

    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final MyProfileService service = new MyProfileService(users, new IdentityAccessPolicy());

    @Test
    void пользователь_видит_свой_профиль() {
        User user = users.save(User.registerUser(new Email("me@example.com"), "Me", "hash"));

        UserResult result = service.me(CurrentActor.identified(user.id(), Set.of(AppRole.USER)));

        assertThat(result.displayName()).isEqualTo("Me");
        assertThat(result.roles()).containsExactly(UserRole.USER);
    }

    @Test
    void гость_не_имеет_профиля() {
        assertThatThrownBy(() -> service.me(CurrentActor.guest()))
            .isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    void пользователь_обновляет_свои_координаты() {
        User user = users.save(User.registerUser(new Email("geo@example.com"), "Geo", "hash"));

        UserResult updated = service.updateLocation(
            CurrentActor.identified(user.id(), Set.of(AppRole.USER)), 55.7558, 37.6173);

        assertThat(updated.latitude()).isEqualTo(55.7558);
        assertThat(updated.longitude()).isEqualTo(37.6173);
    }

    @Test
    void координаты_вне_диапазона_отклоняются_доменом() {
        User user = users.save(User.registerUser(new Email("bad@example.com"), "Bad", "hash"));

        assertThatThrownBy(() -> service.updateLocation(
            CurrentActor.identified(user.id(), Set.of(AppRole.USER)), 91, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`src/test/java/com/plantarena/identity/application/ResolveActorServiceTest.java`:

```java
package com.plantarena.identity.application;

import com.plantarena.identity.application.support.InMemoryUserRepository;
import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.User;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Use case: идентификация субъекта по userId (ADR-005)")
class ResolveActorServiceTest {

    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final ResolveActorService service = new ResolveActorService(users);

    @Test
    void активный_пользователь_резолвится_в_свои_роли() {
        User admin = users.save(User.bootstrapAdmin(new Email("admin@example.com"), "Admin", "hash"));

        CurrentActor actor = service.resolve(admin.id());

        assertThat(actor.isGuest()).isFalse();
        assertThat(actor.userId()).isEqualTo(admin.id());
        assertThat(actor.hasRole(AppRole.ADMIN)).isTrue();
        assertThat(actor.hasRole(AppRole.USER)).isTrue();
    }

    @Test
    void неизвестный_id_отклоняется() {
        assertThatThrownBy(() -> service.resolve(UUID.randomUUID()))
            .isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    void деактивированный_id_отклоняется() {
        User user = users.save(User.registerUser(new Email("off@example.com"), "Off", "hash"));
        user.deactivate();
        users.save(user);

        assertThatThrownBy(() -> service.resolve(user.id()))
            .isInstanceOf(NotIdentifiedException.class);
    }
}
```

`src/test/java/com/plantarena/identity/application/BootstrapAdminServiceTest.java`:

```java
package com.plantarena.identity.application;

import com.plantarena.identity.application.support.FakePasswordHasher;
import com.plantarena.identity.application.support.InMemoryUserRepository;
import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Use case: bootstrap ADMIN из ENV (раздел 2)")
class BootstrapAdminServiceTest {

    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final BootstrapAdminService service =
        new BootstrapAdminService(users, new FakePasswordHasher());

    @Test
    void первый_запуск_создаёт_админа() {
        service.ensureAdmin("admin@example.com", "password-1", "Admin");

        User admin = users.findByEmail(new Email("admin@example.com")).orElseThrow();
        assertThat(admin.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.ADMIN);
    }

    @Test
    void повторный_запуск_не_создаёт_дубликат() {
        service.ensureAdmin("admin@example.com", "password-1", "Admin");
        service.ensureAdmin("admin@example.com", "password-2", "Admin");

        assertThat(users.count()).isOne();
    }
}
```

- [ ] **Step 3: Запустить и убедиться в падении (компиляция)**

```bash
./mvnw -q -Dtest='UserAdministrationServiceTest,MyProfileServiceTest,ResolveActorServiceTest,BootstrapAdminServiceTest' test
```

Expected: COMPILATION ERROR — классы application-слоя не существуют.

- [ ] **Step 4: Реализовать исключения и UserResult**

`src/main/java/com/plantarena/identity/application/UserNotFoundException.java`:

```java
package com.plantarena.identity.application;

import java.util.UUID;

/**
 * Пользователь не найден (переводится в HTTP 404 адаптером).
 * Выражено на едином языке и не знает об HTTP (раздел 13).
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UUID userId) {
        super("Пользователь не найден: " + userId);
    }
}
```

`src/main/java/com/plantarena/identity/application/EmailAlreadyInUseException.java`:

```java
package com.plantarena.identity.application;

import com.plantarena.identity.domain.Email;

/**
 * Email уже используется другой учётной записью (переводится в HTTP 409 адаптером).
 */
public class EmailAlreadyInUseException extends RuntimeException {

    public EmailAlreadyInUseException(Email email) {
        super("Email уже используется: " + email.value());
    }
}
```

`src/main/java/com/plantarena/identity/application/UserResult.java`:

```java
package com.plantarena.identity.application;

import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRole;
import com.plantarena.identity.domain.UserStatus;
import java.util.Set;
import java.util.UUID;

/**
 * Результат use case без passwordHash: пароль никогда не покидает домен (ADR-005).
 */
public record UserResult(UUID id, String email, String displayName, Set<UserRole> roles,
                         UserStatus status, Double latitude, Double longitude) {

    public static UserResult from(User user) {
        return new UserResult(user.id(), user.email().value(), user.displayName(), user.roles(),
            user.status(),
            user.location() == null ? null : user.location().latitude(),
            user.location() == null ? null : user.location().longitude());
    }
}
```

- [ ] **Step 5: Реализовать входные порты**

`src/main/java/com/plantarena/identity/application/port/in/UserAdministrationUseCase.java`:

```java
package com.plantarena.identity.application.port.in;

import com.plantarena.identity.application.UserResult;
import com.plantarena.shared.security.CurrentActor;
import java.util.List;
import java.util.UUID;

/**
 * Служебные операции над пользователями (раздел 13: /users, роли).
 * Доступ проверяет IdentityAccessPolicy.
 */
public interface UserAdministrationUseCase {

    UserResult create(CurrentActor actor, CreateUserCommand command);

    UserResult get(CurrentActor actor, UUID userId);

    UserListResult list(CurrentActor actor, int page, int size);

    UserResult updateDisplayName(CurrentActor actor, UUID userId, String displayName);

    void deactivate(CurrentActor actor, UUID userId);

    UserResult grantModerator(CurrentActor actor, UUID userId);

    UserResult revokeModerator(CurrentActor actor, UUID userId);

    record CreateUserCommand(String email, String password, String displayName) {
    }

    record UserListResult(List<UserResult> items, long total) {
    }
}
```

`src/main/java/com/plantarena/identity/application/port/in/MyProfileUseCase.java`:

```java
package com.plantarena.identity.application.port.in;

import com.plantarena.identity.application.UserResult;
import com.plantarena.shared.security.CurrentActor;

/**
 * Собственный профиль пользователя (раздел 13: /me).
 */
public interface MyProfileUseCase {

    UserResult me(CurrentActor actor);

    UserResult updateLocation(CurrentActor actor, double latitude, double longitude);
}
```

`src/main/java/com/plantarena/identity/application/port/in/ResolveActorUseCase.java`:

```java
package com.plantarena.identity.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/**
 * Идентификация субъекта по userId: демо-заголовок ADR-005 (лаба №1),
 * Spring Security + JWT (лаба №3) — меняется только адаптер.
 */
public interface ResolveActorUseCase {

    CurrentActor resolve(UUID userId);
}
```

`src/main/java/com/plantarena/identity/application/port/in/BootstrapAdminUseCase.java`:

```java
package com.plantarena.identity.application.port.in;

/**
 * Bootstrap-администратор из ENV (раздел 2): первый ADMIN создаётся при старте,
 * повторный запуск не создаёт дубликат.
 */
public interface BootstrapAdminUseCase {

    void ensureAdmin(String email, String password, String displayName);
}
```

- [ ] **Step 6: Реализовать `IdentityAccessPolicy`**

`src/main/java/com/plantarena/identity/application/IdentityAccessPolicy.java`:

```java
package com.plantarena.identity.application;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * AccessPolicy контекста identity: правила единого языка контекста
 * (раздел 2 требований). Владелец ресурса проверяется отдельно от роли.
 */
@Component
public class IdentityAccessPolicy {

    /** Создание и список пользователей — модератор или админ. */
    public void requireUserManagement(CurrentActor actor) {
        requireIdentified(actor);
        if (!actor.hasRole(AppRole.MODERATOR) && !actor.hasRole(AppRole.ADMIN)) {
            throw new AccessDeniedException(
                "Создание и просмотр пользователей доступно модератору или администратору");
        }
    }

    /** Просмотр профиля — сам пользователь, модератор или админ. */
    public void requireViewUser(CurrentActor actor, UUID targetUserId) {
        requireIdentified(actor);
        if (isSelf(actor, targetUserId) || actor.hasRole(AppRole.MODERATOR) || actor.hasRole(AppRole.ADMIN)) {
            return;
        }
        throw new AccessDeniedException(
            "Просмотр профиля доступен самому пользователю, модератору или администратору");
    }

    /** Изменение профиля — владелец или админ; роли и статус меняются отдельными командами. */
    public void requireEditProfile(CurrentActor actor, UUID targetUserId) {
        requireIdentified(actor);
        if (isSelf(actor, targetUserId) || actor.hasRole(AppRole.ADMIN)) {
            return;
        }
        throw new AccessDeniedException("Изменение профиля доступно владельцу или администратору");
    }

    /** Управление ролями и деактивация — только админ. */
    public void requireAdmin(CurrentActor actor) {
        requireIdentified(actor);
        if (!actor.hasRole(AppRole.ADMIN)) {
            throw new AccessDeniedException("Действие доступно только администратору");
        }
    }

    public void requireIdentified(CurrentActor actor) {
        if (actor == null || actor.isGuest()) {
            throw new NotIdentifiedException(
                "Требуется идентифицированный пользователь (X-Demo-User-Id в dev/test, ADR-005)");
        }
    }

    private boolean isSelf(CurrentActor actor, UUID targetUserId) {
        return actor.userId() != null && actor.userId().equals(targetUserId);
    }
}
```

- [ ] **Step 7: Реализовать сервисы use case'ов**

`src/main/java/com/plantarena/identity/application/UserAdministrationService.java`:

```java
package com.plantarena.identity.application;

import com.plantarena.identity.application.port.in.UserAdministrationUseCase;
import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.PasswordHasher;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import com.plantarena.shared.security.CurrentActor;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Служебное управление пользователями. Транзакционные границы — в application
 * (раздел 12), одна транзакция изменяет один агрегат User.
 */
@Service
@Transactional
public class UserAdministrationService implements UserAdministrationUseCase {

    private final UserRepository users;
    private final PasswordHasher passwordHasher;
    private final IdentityAccessPolicy accessPolicy;

    public UserAdministrationService(UserRepository users, PasswordHasher passwordHasher,
                                      IdentityAccessPolicy accessPolicy) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.accessPolicy = accessPolicy;
    }

    @Override
    public UserResult create(CurrentActor actor, CreateUserCommand command) {
        accessPolicy.requireUserManagement(actor);
        Email email = new Email(command.email());
        if (users.findByEmail(email).isPresent()) {
            throw new EmailAlreadyInUseException(email);
        }
        User user = User.registerUser(email, command.displayName(),
            passwordHasher.hash(command.password()));
        return UserResult.from(users.save(user));
    }

    @Override
    public UserResult get(CurrentActor actor, UUID userId) {
        accessPolicy.requireViewUser(actor, userId);
        return UserResult.from(findUser(userId));
    }

    @Override
    public UserListResult list(CurrentActor actor, int page, int size) {
        accessPolicy.requireUserManagement(actor);
        List<UserResult> items = users.findAll(page * size, size).stream()
            .map(UserResult::from)
            .toList();
        return new UserListResult(items, users.count());
    }

    @Override
    public UserResult updateDisplayName(CurrentActor actor, UUID userId, String displayName) {
        accessPolicy.requireEditProfile(actor, userId);
        User user = findUser(userId);
        user.changeDisplayName(displayName);
        return UserResult.from(users.save(user));
    }

    @Override
    public void deactivate(CurrentActor actor, UUID userId) {
        accessPolicy.requireAdmin(actor);
        User user = findUser(userId);
        user.deactivate();
        users.save(user);
    }

    @Override
    public UserResult grantModerator(CurrentActor actor, UUID userId) {
        accessPolicy.requireAdmin(actor);
        User user = findUser(userId);
        user.grantModerator();
        return UserResult.from(users.save(user));
    }

    @Override
    public UserResult revokeModerator(CurrentActor actor, UUID userId) {
        accessPolicy.requireAdmin(actor);
        User user = findUser(userId);
        user.revokeModerator();
        return UserResult.from(users.save(user));
    }

    private User findUser(UUID userId) {
        return users.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
    }
}
```

`src/main/java/com/plantarena/identity/application/MyProfileService.java`:

```java
package com.plantarena.identity.application;

import com.plantarena.identity.application.port.in.MyProfileUseCase;
import com.plantarena.identity.domain.GeoPoint;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import com.plantarena.shared.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Собственный профиль пользователя (/me).
 */
@Service
@Transactional
public class MyProfileService implements MyProfileUseCase {

    private final UserRepository users;
    private final IdentityAccessPolicy accessPolicy;

    public MyProfileService(UserRepository users, IdentityAccessPolicy accessPolicy) {
        this.users = users;
        this.accessPolicy = accessPolicy;
    }

    @Override
    public UserResult me(CurrentActor actor) {
        accessPolicy.requireIdentified(actor);
        return UserResult.from(findUser(actor.userId()));
    }

    @Override
    public UserResult updateLocation(CurrentActor actor, double latitude, double longitude) {
        accessPolicy.requireIdentified(actor);
        User user = findUser(actor.userId());
        user.moveTo(new GeoPoint(latitude, longitude));
        return UserResult.from(users.save(user));
    }

    private User findUser(java.util.UUID userId) {
        return users.findById(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));
    }
}
```

`src/main/java/com/plantarena/identity/application/ResolveActorService.java`:

```java
package com.plantarena.identity.application;

import com.plantarena.identity.application.port.in.ResolveActorUseCase;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import com.plantarena.identity.domain.UserStatus;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Идентификация субъекта по userId (ADR-005): роли всегда из БД,
 * неизвестный/деактивированный ID — ошибка идентификации.
 */
@Service
@Transactional(readOnly = true)
public class ResolveActorService implements ResolveActorUseCase {

    private final UserRepository users;

    public ResolveActorService(UserRepository users) {
        this.users = users;
    }

    @Override
    public CurrentActor resolve(UUID userId) {
        User user = users.findById(userId)
            .orElseThrow(() -> new NotIdentifiedException("Неизвестный X-Demo-User-Id: " + userId));
        if (user.status() != UserStatus.ACTIVE) {
            throw new NotIdentifiedException("Пользователь деактивирован: " + userId);
        }
        Set<AppRole> roles = user.roles().stream()
            .map(role -> AppRole.valueOf(role.name()))
            .collect(Collectors.toUnmodifiableSet());
        return CurrentActor.identified(user.id(), roles);
    }
}
```

`src/main/java/com/plantarena/identity/application/BootstrapAdminService.java`:

```java
package com.plantarena.identity.application;

import com.plantarena.identity.application.port.in.BootstrapAdminUseCase;
import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.PasswordHasher;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bootstrap-админ из ENV: идемпотентен, повторный запуск не создаёт дубликат.
 */
@Service
public class BootstrapAdminService implements BootstrapAdminUseCase {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminService.class);

    private final UserRepository users;
    private final PasswordHasher passwordHasher;

    public BootstrapAdminService(UserRepository users, PasswordHasher passwordHasher) {
        this.users = users;
        this.passwordHasher = passwordHasher;
    }

    @Override
    @Transactional
    public void ensureAdmin(String email, String password, String displayName) {
        Email adminEmail = new Email(email);
        if (users.findByEmail(adminEmail).isPresent()) {
            log.info("Bootstrap ADMIN уже существует, дубль не создаётся: {}", adminEmail.value());
            return;
        }
        users.save(User.bootstrapAdmin(adminEmail, displayName, passwordHasher.hash(password)));
        log.info("Создан bootstrap ADMIN: {}", adminEmail.value());
    }
}
```

- [ ] **Step 8: Запустить и убедиться в прохождении**

```bash
./mvnw -q -Dtest='UserAdministrationServiceTest,MyProfileServiceTest,ResolveActorServiceTest,BootstrapAdminServiceTest' test
```

Expected: PASS.

- [ ] **Step 9: Полный verify (ArchUnit: application не зависит от adapter)**

```bash
./mvnw -q verify
```

Expected: FAIL только в `IdentityApiIT`; ArchUnit зелёный.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/plantarena/identity/application/ src/test/java/com/plantarena/identity/application/
git commit -m "feat: use cases identity, IdentityAccessPolicy и тестовые фейки"
```

---

### Task 5: Миграция identity V2, JPA-адаптер, контрактные тесты репозитория

**Files:**
- Modify: `pom.xml` (добавить test-зависимости `spring-boot-data-jpa-test`, `spring-boot-jdbc-test`)
- Create: `src/main/resources/db/migration/identity/V2__identity_users.sql`
- Create: `src/main/java/com/plantarena/identity/adapter/out/persistence/UserJpaEntity.java`
- Create: `src/main/java/com/plantarena/identity/adapter/out/persistence/RoleJpaEntity.java`
- Create: `src/main/java/com/plantarena/identity/adapter/out/persistence/UserJpaRepository.java`
- Create: `src/main/java/com/plantarena/identity/adapter/out/persistence/RoleJpaRepository.java`
- Create: `src/main/java/com/plantarena/identity/adapter/out/persistence/UserMapper.java`
- Create: `src/main/java/com/plantarena/identity/adapter/out/persistence/JpaUserRepository.java`
- Test: `src/test/java/com/plantarena/identity/UserRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/identity/application/support/InMemoryUserRepositoryContractTest.java`
- Test: `src/test/java/com/plantarena/identity/adapter/out/persistence/JpaUserRepositoryContractIT.java`

**Interfaces:**
- Consumes: домен Task 3 (`UserRepository`, `User.restore`), `SchemaMigrationConfig` итерации 0, `PostgresSupport` итерации 0.
- Produces: bean `UserRepository` (реализация `JpaUserRepository`, `@Repository @Transactional`) для сервисов Task 4; таблицы `identity.app_user`, `identity.role` (справочник USER/MODERATOR/ADMIN засеян миграцией), `identity.user_role` — для `IdentityApiIT` (Task 1) и всех последующих итераций; контрактный тест `UserRepositoryContractTest` гарантирует честность `InMemoryUserRepository`.

- [ ] **Step 1: Добавить test-зависимости в `pom.xml`**

В `<dependencies>` после `archunit-junit6` добавить (версии управляются BOM Boot 4.0.8; пакеты проверены по артефактам 4.0.8):

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-data-jpa-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-jdbc-test</artifactId>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 2: Написать миграцию V2**

`src/main/resources/db/migration/identity/V2__identity_users.sql`:

```sql
-- Контекст identity: пользователи, справочник ролей, связь (раздел 11 требований).
-- Enum → VARCHAR + CHECK; version — optimistic locking; email нормализуется доменом.
CREATE TABLE app_user (
    id               UUID PRIMARY KEY,
    email_normalized VARCHAR(320)  NOT NULL UNIQUE,
    display_name     VARCHAR(100)  NOT NULL,
    password_hash    VARCHAR(100)  NOT NULL,
    status           VARCHAR(20)   NOT NULL CHECK (status IN ('ACTIVE', 'DEACTIVATED')),
    latitude         DOUBLE PRECISION CHECK (latitude BETWEEN -90 AND 90),
    longitude        DOUBLE PRECISION CHECK (longitude BETWEEN -180 AND 180),
    version          BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE role (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE CHECK (code IN ('USER', 'MODERATOR', 'ADMIN'))
);

CREATE TABLE user_role (
    user_id UUID   NOT NULL REFERENCES app_user (id),
    role_id BIGINT NOT NULL REFERENCES role (id),
    PRIMARY KEY (user_id, role_id)
);

INSERT INTO role (code) VALUES ('USER'), ('MODERATOR'), ('ADMIN');
```

- [ ] **Step 3: Написать контрактный тест репозитория (abstract) и наследников**

`src/test/java/com/plantarena/identity/UserRepositoryContractTest.java`:

```java
package com.plantarena.identity;

import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.GeoPoint;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import com.plantarena.identity.domain.UserRole;
import com.plantarena.identity.domain.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт порта UserRepository (раздел 14.2): fake и JPA-адаптер ведут себя
 * одинаково. Сравнение по полям — доменные объекты не переопределяют equals.
 */
@DisplayName("Контракт UserRepository: fake и JPA ведут себя одинаково")
public abstract class UserRepositoryContractTest {

    protected abstract UserRepository repository();

    @Test
    void сохранённый_пользователь_находится_по_id_и_нормализованному_email() {
        User user = User.registerUser(new Email("Alice@Example.COM"), "Alice", "hash-1");
        repository().save(user);

        var byId = repository().findById(user.id()).orElseThrow();
        assertThat(byId.email().value()).isEqualTo("alice@example.com");
        assertThat(byId.displayName()).isEqualTo("Alice");
        assertThat(byId.passwordHash()).isEqualTo("hash-1");
        assertThat(byId.roles()).containsExactly(UserRole.USER);
        assertThat(byId.status()).isEqualTo(UserStatus.ACTIVE);

        var byEmail = repository().findByEmail(new Email("alice@example.com")).orElseThrow();
        assertThat(byEmail.id()).isEqualTo(user.id());
    }

    @Test
    void обновление_пользователя_сохраняется_роли_профиль_координаты_статус() {
        User user = User.registerUser(new Email("bob@example.com"), "Bob", "hash-2");
        repository().save(user);

        user.grantModerator();
        user.changeDisplayName("Bob II");
        user.moveTo(new GeoPoint(55.7558, 37.6173));
        repository().save(user);

        var reloaded = repository().findById(user.id()).orElseThrow();
        assertThat(reloaded.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.MODERATOR);
        assertThat(reloaded.displayName()).isEqualTo("Bob II");
        assertThat(reloaded.location()).isEqualTo(new GeoPoint(55.7558, 37.6173));
    }

    @Test
    void список_и_количество_учитывают_всех_сохранённых_пользователей() {
        for (int i = 0; i < 3; i++) {
            repository().save(User.registerUser(
                new Email("user" + i + "@example.com"), "User " + i, "hash"));
        }

        assertThat(repository().count()).isEqualTo(3);
        assertThat(repository().findAll(0, 2)).hasSize(2);
        assertThat(repository().findAll(2, 2)).hasSize(1);
    }

    @Test
    void деактивация_и_снятие_роли_сохраняются() {
        User user = User.registerUser(new Email("carol@example.com"), "Carol", "hash-3");
        user.grantModerator();
        repository().save(user);

        user.revokeModerator();
        user.deactivate();
        repository().save(user);

        var reloaded = repository().findById(user.id()).orElseThrow();
        assertThat(reloaded.roles()).containsExactly(UserRole.USER);
        assertThat(reloaded.status()).isEqualTo(UserStatus.DEACTIVATED);
    }
}
```

`src/test/java/com/plantarena/identity/application/support/InMemoryUserRepositoryContractTest.java`:

```java
package com.plantarena.identity.application.support;

import com.plantarena.identity.UserRepositoryContractTest;
import com.plantarena.identity.domain.UserRepository;
import org.junit.jupiter.api.DisplayName;

@DisplayName("Контракт UserRepository: in-memory fake")
class InMemoryUserRepositoryContractTest extends UserRepositoryContractTest {

    private final InMemoryUserRepository repository = new InMemoryUserRepository();

    @Override
    protected UserRepository repository() {
        return repository;
    }
}
```

`src/test/java/com/plantarena/identity/adapter/out/persistence/JpaUserRepositoryContractIT.java`:

```java
package com.plantarena.identity.adapter.out.persistence;

import com.plantarena.identity.UserRepositoryContractTest;
import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import com.plantarena.support.PostgresSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Контракт UserRepository на JPA + PostgreSQL (Testcontainers, раздел 14.2).
 * Миграции выполняет SchemaMigrationConfig (ADR-003), H2 не используется.
 */
@DisplayName("Контракт UserRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaUserRepository.class})
class JpaUserRepositoryContractIT extends UserRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaUserRepository repository;

    @Override
    protected UserRepository repository() {
        return repository;
    }

    @Test
    @DisplayName("дубликат email отклоняется ограничением БД (UNIQUE)")
    void дубликат_email_отклоняется_ограничением_бд() {
        repository().save(User.registerUser(new Email("dup@example.com"), "First", "hash"));
        User duplicate = User.registerUser(new Email("dup@example.com"), "Second", "hash");

        assertThatThrownBy(() -> repository().save(duplicate))
            .isInstanceOf(RuntimeException.class);
    }
}
```

Примечание: `SchemaMigrationConfig` — из `com.plantarena.config` (импорт явный). Если Hibernate `validate` упадёт из-за порядка бинов (маловероятно, см. Task 6 итерации 0), перенести вызов `migrateAll()` в `@PostConstruct`.

- [ ] **Step 4: Запустить и убедиться в падении (компиляция JPA-наследника)**

```bash
./mvnw -q -Dit.name=JpaUserRepositoryContractIT verify
```

Expected: COMPILATION ERROR — `JpaUserRepository` и JPA-сущности не существуют (компилируются все test-sources, включая JPA-наследника контракта). Это красное состояние Task 5. Контрактный тест fake'а (`InMemoryUserRepositoryContractTest`) выполнится и позеленеет вместе с реализацией на Step 8.

- [ ] **Step 5: Реализовать JPA-сущности**

`src/main/java/com/plantarena/identity/adapter/out/persistence/UserJpaEntity.java`:

```java
package com.plantarena.identity.adapter.out.persistence;

import com.plantarena.identity.domain.UserRole;
import com.plantarena.identity.domain.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * JPA-модель app_user (схема identity, раздел 11). Маппинг на домен явный (UserMapper).
 */
@Entity
@Table(name = "app_user", schema = "identity")
public class UserJpaEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "email_normalized", nullable = false, unique = true)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_role", schema = "identity",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<RoleJpaEntity> roles = new LinkedHashSet<>();

    @Version
    @Column(nullable = false)
    private long version;

    protected UserJpaEntity() {
    }

    UserJpaEntity(UUID id, String email) {
        this.id = id;
        this.email = email;
    }

    void update(String displayName, String passwordHash, UserStatus status,
                Double latitude, Double longitude) {
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.status = status;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    UUID id() {
        return id;
    }

    String email() {
        return email;
    }

    String displayName() {
        return displayName;
    }

    String passwordHash() {
        return passwordHash;
    }

    UserStatus status() {
        return status;
    }

    Double latitude() {
        return latitude;
    }

    Double longitude() {
        return longitude;
    }

    Set<RoleJpaEntity> roles() {
        return roles;
    }

    long versionValue() {
        return version;
    }
}
```

`src/main/java/com/plantarena/identity/adapter/out/persistence/RoleJpaEntity.java`:

```java
package com.plantarena.identity.adapter.out.persistence;

import com.plantarena.identity.domain.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA-модель справочника role (схема identity): строки засеиваются миграцией V2.
 */
@Entity
@Table(name = "role", schema = "identity")
public class RoleJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private UserRole code;

    protected RoleJpaEntity() {
    }

    UserRole code() {
        return code;
    }
}
```

- [ ] **Step 6: Реализовать Spring Data репозитории и маппер**

`src/main/java/com/plantarena/identity/adapter/out/persistence/UserJpaRepository.java`:

```java
package com.plantarena.identity.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserJpaRepository extends JpaRepository<UserJpaEntity, UUID> {

    Optional<UserJpaEntity> findByEmail(String email);
}
```

`src/main/java/com/plantarena/identity/adapter/out/persistence/RoleJpaRepository.java`:

```java
package com.plantarena.identity.adapter.out.persistence;

import com.plantarena.identity.domain.UserRole;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface RoleJpaRepository extends JpaRepository<RoleJpaEntity, Long> {

    Optional<RoleJpaEntity> findByCode(UserRole code);
}
```

`src/main/java/com/plantarena/identity/adapter/out/persistence/UserMapper.java`:

```java
package com.plantarena.identity.adapter.out.persistence;

import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.GeoPoint;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRole;
import java.util.EnumSet;
import java.util.stream.Collectors;

/**
 * Явный маппинг JPA-модели на домен (раздел 5 требований).
 */
final class UserMapper {

    private UserMapper() {
    }

    static User toDomain(UserJpaEntity entity) {
        java.util.Set<UserRole> roles = entity.roles().stream()
            .map(RoleJpaEntity::code)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(UserRole.class)));
        GeoPoint location = entity.latitude() == null || entity.longitude() == null
            ? null
            : new GeoPoint(entity.latitude(), entity.longitude());
        return User.restore(entity.id(), new Email(entity.email()), entity.displayName(),
            entity.passwordHash(), roles, entity.status(), location, entity.versionValue());
    }
}
```

- [ ] **Step 7: Реализовать `JpaUserRepository`**

`src/main/java/com/plantarena/identity/adapter/out/persistence/JpaUserRepository.java`:

```java
package com.plantarena.identity.adapter.out.persistence;

import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import com.plantarena.identity.domain.UserRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация порта UserRepository на JPA (схема identity).
 * Роли ссылаются на засеянный справочник role; email уникален ограничением БД.
 */
@Repository
@Transactional
public class JpaUserRepository implements UserRepository {

    private final UserJpaRepository users;
    private final RoleJpaRepository roles;

    public JpaUserRepository(UserJpaRepository users, RoleJpaRepository roles) {
        this.users = users;
        this.roles = roles;
    }

    @Override
    public User save(User user) {
        UserJpaEntity entity = users.findById(user.id())
            .orElseGet(() -> new UserJpaEntity(user.id(), user.email().value()));
        entity.update(user.displayName(), user.passwordHash(), user.status(),
            latitudeOf(user), longitudeOf(user));
        entity.roles().clear();
        user.roles().forEach(role -> entity.roles().add(roleEntity(role)));
        return UserMapper.toDomain(users.saveAndFlush(entity));
    }

    @Override
    public Optional<User> findById(UUID id) {
        return users.findById(id).map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return users.findByEmail(email.value()).map(UserMapper::toDomain);
    }

    @Override
    public List<User> findAll(int offset, int limit) {
        // offset всегда page-aligned (PaginationParams: offset = page * size)
        return users.findAll(PageRequest.of(offset / limit, limit)).stream()
            .map(UserMapper::toDomain)
            .toList();
    }

    @Override
    public long count() {
        return users.count();
    }

    private RoleJpaEntity roleEntity(UserRole role) {
        return roles.findByCode(role)
            .orElseThrow(() -> new IllegalStateException(
                "Роль отсутствует в справочнике identity.role: " + role));
    }

    private Double latitudeOf(User user) {
        return user.location() == null ? null : user.location().latitude();
    }

    private Double longitudeOf(User user) {
        return user.location() == null ? null : user.location().longitude();
    }
}
```

- [ ] **Step 8: Запустить контрактные тесты**

```bash
./mvnw -q -Dtest='InMemoryUserRepositoryContractTest' test && ./mvnw -q -Dit.name=JpaUserRepositoryContractIT verify
```

Expected: PASS оба (fake и JPA проходят один и тот же контракт; дубликат email отклоняется БД).

- [ ] **Step 9: Полный verify**

```bash
./mvnw -q verify
```

Expected: FAIL только в `IdentityApiIT` (контроллеров ещё нет; таблицы уже есть — `adminId()` больше не падает на отсутствии таблицы, но эндпоинты 404). ArchUnit зелёный (JPA только в `adapter.out.persistence`).

- [ ] **Step 10: Commit**

```bash
git add pom.xml src/main/resources/db/migration/identity/ src/main/java/com/plantarena/identity/adapter/out/persistence/ src/test/java/com/plantarena/identity/
git commit -m "feat: миграция identity V2, JPA-адаптер UserRepository и контрактные тесты"
```

---

### Task 6: Адаптеры идентификации — BCrypt, демо-провайдер, bootstrap ADMIN, wiring

**Files:**
- Modify: `pom.xml` (добавить `spring-security-crypto`)
- Modify: `src/main/resources/application.yml` (свойства bootstrap-админа)
- Create: `src/main/java/com/plantarena/identity/adapter/out/crypto/SpringSecurityPasswordHasher.java`
- Create: `src/main/java/com/plantarena/identity/adapter/in/web/DemoHeaderCurrentActorProvider.java`
- Create: `src/main/java/com/plantarena/identity/adapter/in/jobs/BootstrapAdminRunner.java`
- Create: `src/main/java/com/plantarena/config/IdentityWiringConfig.java`
- Test: `src/test/java/com/plantarena/config/IdentityWiringConfigTest.java`

**Interfaces:**
- Consumes: `PasswordHasher` (Task 3), `ResolveActorUseCase`/`BootstrapAdminUseCase` (Task 4), `CurrentActorProvider` (Task 2).
- Produces: bean `CurrentActorProvider` (в dev/test — `DemoHeaderCurrentActorProvider`, константа `DemoHeaderCurrentActorProvider.DEMO_USER_ID_HEADER = "X-Demo-User-Id"`; в обычном профиле — `IllegalStateException` при старте, ADR-005); bean `PasswordHasher` (`SpringSecurityPasswordHasher`, BCrypt); `BootstrapAdminRunner` (ApplicationRunner, свойства `plantarena.bootstrap-admin.email/password/display-name` из ENV `BOOTSTRAP_ADMIN_EMAIL/PASSWORD/DISPLAY_NAME`). Используется Task 7 (контроллеры внедряют `CurrentActorProvider`).

- [ ] **Step 1: Написать падающий тест wiring'а**

`src/test/java/com/plantarena/config/IdentityWiringConfigTest.java`:

```java
package com.plantarena.config;

import com.plantarena.identity.adapter.in.web.DemoHeaderCurrentActorProvider;
import com.plantarena.identity.application.port.in.ResolveActorUseCase;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Связывание адаптера идентификации (ADR-005)")
class IdentityWiringConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(IdentityWiringConfig.class)
        .withBean(ResolveActorUseCase.class,
            () -> userId -> CurrentActor.identified(userId, Set.of()));

    @Test
    void обычный_профиль_без_адаптера_идентификации_падает_при_старте() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasMessageContaining("Адаптер идентификации не настроен");
        });
    }

    @Test
    void профиль_test_подключает_демо_адаптер() {
        runner.withPropertyValues("spring.profiles.active=test").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CurrentActorProvider.class);
            assertThat(context).hasSingleBean(DemoHeaderCurrentActorProvider.class);
        });
    }

    @Test
    void профиль_dev_подключает_демо_адаптер() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DemoHeaderCurrentActorProvider.class);
        });
    }
}
```

- [ ] **Step 2: Запустить и убедиться в падении (компиляция)**

```bash
./mvnw -q -Dtest=IdentityWiringConfigTest test
```

Expected: COMPILATION ERROR — `IdentityWiringConfig` и `DemoHeaderCurrentActorProvider` не существуют.

- [ ] **Step 3: Добавить `spring-security-crypto` в `pom.xml`**

В `<dependencies>` после `spring-boot-starter-validation` (версия управляется BOM Boot):

```xml
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-crypto</artifactId>
    </dependency>
```

- [ ] **Step 4: Реализовать адаптер `PasswordHasher`**

`src/main/java/com/plantarena/identity/adapter/out/crypto/SpringSecurityPasswordHasher.java`:

```java
package com.plantarena.identity.adapter.out.crypto;

import com.plantarena.identity.domain.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Адаптер порта PasswordHasher на spring-security-crypto (раздел 2 требований):
 * домен не знает о реализации хэширования.
 */
@Component
public class SpringSecurityPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return encoder.matches(rawPassword, passwordHash);
    }
}
```

- [ ] **Step 5: Реализовать демо-провайдер `CurrentActor`**

`src/main/java/com/plantarena/identity/adapter/in/web/DemoHeaderCurrentActorProvider.java`:

```java
package com.plantarena.identity.adapter.in.web;

import com.plantarena.identity.application.port.in.ResolveActorUseCase;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.shared.security.NotIdentifiedException;
import java.util.UUID;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Демо-идентификация через X-Demo-User-Id (ADR-005): только профили dev/test,
 * связывание — config.IdentityWiringConfig. Отсутствие заголовка = гость;
 * неизвестный/неактивный ID = ошибка; роли всегда из БД, не из заголовка.
 * Это механизм демонстрации, не аутентификация.
 */
public class DemoHeaderCurrentActorProvider implements CurrentActorProvider {

    public static final String DEMO_USER_ID_HEADER = "X-Demo-User-Id";

    private final ResolveActorUseCase resolveActor;

    public DemoHeaderCurrentActorProvider(ResolveActorUseCase resolveActor) {
        this.resolveActor = resolveActor;
    }

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
        return resolveActor.resolve(userId);
    }
}
```

- [ ] **Step 6: Реализовать bootstrap-раннер и свойства**

Добавить в `src/main/resources/application.yml` (в конец файла):

```yaml

plantarena:
  bootstrap-admin:
    email: ${BOOTSTRAP_ADMIN_EMAIL:}
    password: ${BOOTSTRAP_ADMIN_PASSWORD:}
    display-name: ${BOOTSTRAP_ADMIN_DISPLAY_NAME:Admin}
```

`src/main/java/com/plantarena/identity/adapter/in/jobs/BootstrapAdminRunner.java`:

```java
package com.plantarena.identity.adapter.in.jobs;

import com.plantarena.identity.application.port.in.BootstrapAdminUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Bootstrap-админ из ENV (раздел 2): первый ADMIN создаётся при старте,
 * повторный запуск не создаёт дубликат (идемпотентность — в use case).
 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final BootstrapAdminUseCase bootstrapAdmin;
    private final String email;
    private final String password;
    private final String displayName;

    public BootstrapAdminRunner(BootstrapAdminUseCase bootstrapAdmin,
                                @Value("${plantarena.bootstrap-admin.email:}") String email,
                                @Value("${plantarena.bootstrap-admin.password:}") String password,
                                @Value("${plantarena.bootstrap-admin.display-name:Admin}") String displayName) {
        this.bootstrapAdmin = bootstrapAdmin;
        this.email = email;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank()) {
            log.info("Bootstrap ADMIN не настроен (BOOTSTRAP_ADMIN_EMAIL пуст) — пропускается");
            return;
        }
        if (password.isBlank()) {
            throw new IllegalStateException(
                "BOOTSTRAP_ADMIN_PASSWORD обязателен, если задан BOOTSTRAP_ADMIN_EMAIL");
        }
        bootstrapAdmin.ensureAdmin(email, password, displayName);
    }
}
```

- [ ] **Step 7: Реализовать `IdentityWiringConfig`**

`src/main/java/com/plantarena/config/IdentityWiringConfig.java`:

```java
package com.plantarena.config;

import com.plantarena.identity.adapter.in.web.DemoHeaderCurrentActorProvider;
import com.plantarena.identity.application.port.in.ResolveActorUseCase;
import com.plantarena.shared.security.CurrentActorProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Связывание адаптера идентификации (ADR-005). config — единственное место,
 * знающее несколько контекстов (раздел 10.2, правило 9). Обычный профиль без
 * адаптера идентификации завершается явной ошибкой конфигурации при старте;
 * Spring Security + JWT появится в лабе №3 (замена только этого бина).
 */
@Configuration
public class IdentityWiringConfig {

    @Bean
    public CurrentActorProvider currentActorProvider(Environment environment,
                                                     ResolveActorUseCase resolveActorUseCase) {
        if (environment.matchesProfiles("dev", "test")) {
            return new DemoHeaderCurrentActorProvider(resolveActorUseCase);
        }
        throw new IllegalStateException(
            "Адаптер идентификации не настроен: заголовок X-Demo-User-Id разрешён только "
                + "в профилях dev/test (ADR-005); Spring Security + JWT появится в лабе №3");
    }
}
```

- [ ] **Step 8: Запустить и убедиться в прохождении**

```bash
./mvnw -q -Dtest=IdentityWiringConfigTest test
```

Expected: PASS.

- [ ] **Step 9: Полный verify (контексты IT поднимаются с профилем test)**

```bash
./mvnw -q verify
```

Expected: FAIL только в `IdentityApiIT` (эндпоинтов ещё нет). Все IT итерации 0 зелёные: их контексты стартуют с профилем `test` → `IdentityWiringConfig` отдаёт демо-провайдер; `BootstrapAdminRunner` создаёт админа `admin@plantarena.local` (идемпотентно при повторных контекстах).

- [ ] **Step 10: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/main/java/com/plantarena/identity/adapter/ src/main/java/com/plantarena/config/IdentityWiringConfig.java src/test/java/com/plantarena/config/IdentityWiringConfigTest.java
git commit -m "feat: адаптеры идентификации — BCrypt, демо-провайдер X-Demo-User-Id, bootstrap ADMIN, wiring с ошибкой конфигурации"
```

---

### Task 7: REST /users и /me — приёмочный IT становится зелёным

**Files:**
- Create: `src/main/java/com/plantarena/identity/adapter/in/web/CreateUserRequest.java`
- Create: `src/main/java/com/plantarena/identity/adapter/in/web/UpdateUserRequest.java`
- Create: `src/main/java/com/plantarena/identity/adapter/in/web/UpdateLocationRequest.java`
- Create: `src/main/java/com/plantarena/identity/adapter/in/web/UserResponse.java`
- Create: `src/main/java/com/plantarena/identity/adapter/in/web/UserController.java`
- Create: `src/main/java/com/plantarena/identity/adapter/in/web/MeController.java`
- Create: `src/main/java/com/plantarena/identity/adapter/in/web/IdentityExceptionHandler.java`

**Interfaces:**
- Consumes: `UserAdministrationUseCase`, `MyProfileUseCase` (Task 4), `CurrentActorProvider` (Task 6), `PaginationParams` (Task 2), `ApiError`/`TraceIdFilter` (итерация 0).
- Produces: REST-контракты раздела 13: `POST /api/v1/users` (201 + Location), `GET /api/v1/users?page&size` (200, `X-Total-Count`), `GET /api/v1/users/{id}` (200), `PATCH /api/v1/users/{id}` (200), `DELETE /api/v1/users/{id}` (204), `PUT|DELETE /api/v1/users/{id}/roles/moderator` (200), `GET /api/v1/me` (200), `PUT /api/v1/me/location` (200). Ответ — `UserResponse(id, email, displayName, roles, status, latitude, longitude)` без passwordHash. Swagger: тег `identity`, operationId с префиксом `identity-`.

- [ ] **Step 1: Реализовать DTO**

`src/main/java/com/plantarena/identity/adapter/in/web/CreateUserRequest.java`:

```java
package com.plantarena.identity.adapter.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Вход DTO создания пользователя: пароль разрешён (ADR-005), роли и статус
 * передавать нельзя — полей в DTO нет (раздел 2 требований).
 */
public record CreateUserRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotBlank @Size(min = 1, max = 100) String displayName) {
}
```

`src/main/java/com/plantarena/identity/adapter/in/web/UpdateUserRequest.java`:

```java
package com.plantarena.identity.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Вход DTO изменения профиля: только displayName; произвольные roles/status
 * запрещены (раздел 13). Неизвестные поля JSON игнорируются.
 */
public record UpdateUserRequest(
        @NotBlank @Size(min = 1, max = 100) String displayName) {
}
```

`src/main/java/com/plantarena/identity/adapter/in/web/UpdateLocationRequest.java`:

```java
package com.plantarena.identity.adapter.in.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Вход DTO координат: диапазоны дублируют доменную проверку GeoPoint
 * (Bean Validation — на request DTO, раздел 17).
 */
public record UpdateLocationRequest(
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude) {
}
```

`src/main/java/com/plantarena/identity/adapter/in/web/UserResponse.java`:

```java
package com.plantarena.identity.adapter.in.web;

import com.plantarena.identity.application.UserResult;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ответ без passwordHash: пароль никогда не присутствует в ответах (ADR-005).
 */
public record UserResponse(UUID id, String email, String displayName, Set<String> roles,
                           String status, Double latitude, Double longitude) {

    static UserResponse from(UserResult user) {
        return new UserResponse(user.id(), user.email(), user.displayName(),
            user.roles().stream().map(Enum::name).collect(Collectors.toSet()),
            user.status().name(), user.latitude(), user.longitude());
    }
}
```

- [ ] **Step 2: Реализовать контроллеры**

`src/main/java/com/plantarena/identity/adapter/in/web/UserController.java`:

```java
package com.plantarena.identity.adapter.in.web;

import com.plantarena.identity.application.UserResult;
import com.plantarena.identity.application.port.in.UserAdministrationUseCase;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Служебные ручки пользователей (раздел 13). Контроллер обращается только
 * к входным портам application (правило 10.2.7).
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "identity")
public class UserController {

    private final UserAdministrationUseCase administration;
    private final CurrentActorProvider currentActorProvider;

    public UserController(UserAdministrationUseCase administration,
                          CurrentActorProvider currentActorProvider) {
        this.administration = administration;
        this.currentActorProvider = currentActorProvider;
    }

    @PostMapping
    @Operation(operationId = "identity-create-user",
        summary = "Создать пользователя (только роль USER; модератор/админ)")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        UserResult created = administration.create(actor, new UserAdministrationUseCase.CreateUserCommand(
            request.email(), request.password(), request.displayName()));
        return ResponseEntity
            .created(URI.create("/api/v1/users/" + created.id()))
            .body(UserResponse.from(created));
    }

    @GetMapping
    @Operation(operationId = "identity-list-users",
        summary = "Список пользователей (модератор/админ), X-Total-Count")
    public ResponseEntity<List<UserResponse>> list(@RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        CurrentActor actor = currentActorProvider.currentActor();
        PaginationParams pagination = PaginationParams.of(page, size);
        UserAdministrationUseCase.UserListResult result =
            administration.list(actor, pagination.page(), pagination.size());
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(result.total()))
            .body(result.items().stream().map(UserResponse::from).toList());
    }

    @GetMapping("/{id}")
    @Operation(operationId = "identity-get-user",
        summary = "Профиль пользователя (сам пользователь, модератор/админ)")
    public UserResponse get(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return UserResponse.from(administration.get(actor, id));
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "identity-update-user",
        summary = "Изменить displayName (владелец/админ); roles/status менять нельзя")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        CurrentActor actor = currentActorProvider.currentActor();
        return UserResponse.from(administration.updateDisplayName(actor, id, request.displayName()));
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "identity-deactivate-user",
        summary = "Деактивировать пользователя (админ)")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        administration.deactivate(actor, id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/roles/moderator")
    @Operation(operationId = "identity-grant-moderator",
        summary = "Назначить роль MODERATOR (админ, идемпотентно)")
    public UserResponse grantModerator(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return UserResponse.from(administration.grantModerator(actor, id));
    }

    @DeleteMapping("/{id}/roles/moderator")
    @Operation(operationId = "identity-revoke-moderator",
        summary = "Снять роль MODERATOR, не удаляя USER (админ, идемпотентно)")
    public UserResponse revokeModerator(@PathVariable UUID id) {
        CurrentActor actor = currentActorProvider.currentActor();
        return UserResponse.from(administration.revokeModerator(actor, id));
    }
}
```

`src/main/java/com/plantarena/identity/adapter/in/web/MeController.java`:

```java
package com.plantarena.identity.adapter.in.web;

import com.plantarena.identity.application.port.in.MyProfileUseCase;
import com.plantarena.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Собственный профиль (/me): только идентифицированный пользователь.
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "identity")
public class MeController {

    private final MyProfileUseCase myProfile;
    private final CurrentActorProvider currentActorProvider;

    public MeController(MyProfileUseCase myProfile, CurrentActorProvider currentActorProvider) {
        this.myProfile = myProfile;
        this.currentActorProvider = currentActorProvider;
    }

    @GetMapping
    @Operation(operationId = "identity-me", summary = "Собственный профиль без passwordHash")
    public UserResponse me() {
        return UserResponse.from(myProfile.me(currentActorProvider.currentActor()));
    }

    @PutMapping("/location")
    @Operation(operationId = "identity-update-my-location",
        summary = "Обновить координаты (применяются к следующей global epoch)")
    public UserResponse updateLocation(@Valid @RequestBody UpdateLocationRequest request) {
        return UserResponse.from(myProfile.updateLocation(
            currentActorProvider.currentActor(), request.latitude(), request.longitude()));
    }
}
```

- [ ] **Step 3: Реализовать `IdentityExceptionHandler`**

`src/main/java/com/plantarena/identity/adapter/in/web/IdentityExceptionHandler.java`:

```java
package com.plantarena.identity.adapter.in.web;

import com.plantarena.identity.application.EmailAlreadyInUseException;
import com.plantarena.identity.application.UserNotFoundException;
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
 * Перевод исключений identity в ProblemDetail-подобное тело (раздел 13).
 * Живёт в adapter.in.web: shared не зависит от контекстов (правило 10.2.8);
 * Spring выбирает самый специфичный обработчик среди всех advice.
 */
@RestControllerAdvice
public class IdentityExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiError> notFound(UserNotFoundException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(EmailAlreadyInUseException.class)
    public ResponseEntity<ApiError> conflict(EmailAlreadyInUseException e, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "EMAIL_ALREADY_IN_USE", e.getMessage(), request);
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

- [ ] **Step 4: Запустить приёмочный IT — должен стать зелёным**

```bash
./mvnw -q -Dit.name=IdentityApiIT verify
```

Expected: PASS — все сценарии раздела 2 проходят через HTTP.

Если отдельные тесты падают: читать сообщение (например, 404 на `/api/v1/me/location` — опечатка пути; 500 вместо 409 — забыт обработчик) и править соответствующий файл; не ослаблять утверждения тестов.

- [ ] **Step 5: Полный verify**

```bash
./mvnw -q verify
```

Expected: `BUILD SUCCESS` — surefire (ArchUnit, домен, application, контракты, wiring), failsafe (IT итерации 0 + `IdentityApiIT`, `JpaUserRepositoryContractIT`), JaCoCo check ≥ 70%.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/plantarena/identity/adapter/in/web/
git commit -m "feat: REST /users и /me (identity) — сценарии раздела 2 зелёные через HTTP"
```

---

### Task 8: Swagger-документация демо-идентификации, Docker Compose, docs

**Files:**
- Modify: `src/main/java/com/plantarena/config/OpenApiConfig.java`
- Modify: `docker-compose.yml`
- Modify: `.env.example`
- Modify: `docs/domain/aggregates.md`
- Modify: `README.md`

**Interfaces:**
- Produces: Swagger UI документирует заголовок `X-Demo-User-Id` на всех операциях (ADR-005: «Механизм описан в Swagger»); Compose передаёт `SPRING_PROFILES_ACTIVE`, `BOOTSTRAP_ADMIN_*` из ENV; `aggregates.md` — защищающие тесты инвариантов identity; README — как демо-идентифицироваться.

- [ ] **Step 1: Дополнить `OpenApiConfig` (полное новое содержимое)**

`src/main/java/com/plantarena/config/OpenApiConfig.java`:

```java
package com.plantarena.config;

import com.plantarena.identity.adapter.in.web.DemoHeaderCurrentActorProvider;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.Operation;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Единая OpenAPI-спецификация монолита (раздел 13 требований).
 * Демо-идентификация документируется в Swagger (ADR-005).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI plantArenaOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Plant Arena API")
                .version("1")
                .description("Турниры растений: пользователи, файлы и растения, модерация, "
                    + "закрытые и глобальный турниры, голосование, лента"));
    }

    /**
     * Добавляет описание демо-заголовка ко всем операциям: отсутствие заголовка
     * означает гостя (ADR-005). Это механизм демонстрации, не аутентификация.
     */
    @Bean
    public OperationCustomizer demoUserIdHeaderCustomizer() {
        return (Operation operation, org.springframework.web.method.HandlerMethod handlerMethod) -> {
            operation.addParametersItem(new Parameter()
                .in("header")
                .name(DemoHeaderCurrentActorProvider.DEMO_USER_ID_HEADER)
                .description("Демо-идентификация (только профили dev/test, ADR-005): UUID "
                    + "существующего активного пользователя. Отсутствие заголовка = гость. "
                    + "Роли всегда берутся из БД. Не аутентификация.")
                .required(false)
                .schema(new StringSchema()));
            return operation;
        };
    }
}
```

- [ ] **Step 2: Проверить, что Swagger-IT остаётся зелёным**

```bash
./mvnw -q -Dit.name=OpenApiDocsIT verify
```

Expected: PASS (спецификация по-прежнему доступна; кастомизатор не ломает `/v3/api-docs`).

- [ ] **Step 3: Дополнить `docker-compose.yml` (блок environment сервиса app)**

В сервисе `app` секцию `environment` привести к виду (добавлены 4 переменные):

```yaml
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/plantarena
      DB_USERNAME: ${DB_USERNAME:-postgres}
      DB_PASSWORD: ${DB_PASSWORD:-postgres}
      SERVER_PORT: 8080
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-dev}
      BOOTSTRAP_ADMIN_EMAIL: ${BOOTSTRAP_ADMIN_EMAIL:-}
      BOOTSTRAP_ADMIN_PASSWORD: ${BOOTSTRAP_ADMIN_PASSWORD:-}
      BOOTSTRAP_ADMIN_DISPLAY_NAME: ${BOOTSTRAP_ADMIN_DISPLAY_NAME:-Admin}
```

- [ ] **Step 4: Обновить `.env.example` (полное новое содержимое)**

```
# Примеры параметров. Реальные значения передаются через ENV, не коммитятся.
DB_USERNAME=postgres
DB_PASSWORD=change-me
SPRING_PROFILES_ACTIVE=dev
BOOTSTRAP_ADMIN_EMAIL=admin@plantarena.local
BOOTSTRAP_ADMIN_PASSWORD=change-me-admin
BOOTSTRAP_ADMIN_DISPLAY_NAME=Admin
```

- [ ] **Step 5: Заполнить защищающие тесты в `docs/domain/aggregates.md`**

В таблице заменить строку identity (колонка «Защищающий тест»):

было:

```markdown
| identity | `User` | роли, профиль, координаты, хэш пароля, статус | USER всегда присутствует; роли меняются только отдельными командами; координаты в допустимых диапазонах | создать USER, изменить профиль, назначить/снять роль, деактивировать | — | (итерация 1) |
```

стало:

```markdown
| identity | `User` | роли, профиль, координаты, хэш пароля, статус | USER всегда присутствует; роли меняются только отдельными командами; координаты в допустимых диапазонах | создать USER, изменить профиль, назначить/снять роль, деактивировать | — | `UserTest` (роли/USER всегда), `GeoPointTest` (диапазоны), `UserAdministrationServiceTest`, `IdentityApiIT` (через HTTP) |
```

- [ ] **Step 6: Дополнить `README.md`**

После секции «Запуск» добавить раздел:

```markdown
## Демо-идентификация (ADR-005)

В профилях `dev`/`test` пользователь передаётся заголовком `X-Demo-User-Id: <UUID>`
(см. Swagger UI). Отсутствие заголовка = гость; неизвестный/деактивированный ID —
ошибка 401; роли всегда берутся из БД. Это механизм демонстрации, не аутентификация.
Обычный профиль без адаптера идентификации падает при старте с явной ошибкой.
Bootstrap-админ: `BOOTSTRAP_ADMIN_EMAIL` + `BOOTSTRAP_ADMIN_PASSWORD` (ENV),
повторный запуск дубликат не создаёт.
```

- [ ] **Step 7: Полный verify**

```bash
./mvnw -q verify
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/plantarena/config/OpenApiConfig.java docker-compose.yml .env.example docs/domain/aggregates.md README.md
git commit -m "docs: swagger X-Demo-User-Id, compose ENV bootstrap-админа, защищающие тесты агрегата User"
```

---

### Task 9: Финальная проверка итерации 1 и merge

**Files:**
- Modify: при необходимости — файлы предыдущих задач (только фиксы).

**Interfaces:**
- Produces: зелёный `./mvnw verify` на ветке `feat/iteration-1-identity`; merge в `main` (локально); чекпоинт-отчёт для review.

- [ ] **Step 1: Полный прогон**

```bash
./mvnw verify
```

Expected: `BUILD SUCCESS`. В логе: Surefire — ArchUnit (`ContextBoundaryTest`, `LayerRulesTest`), домен (`UserTest`, `EmailTest`, `GeoPointTest`), application (`UserAdministrationServiceTest`, `MyProfileServiceTest`, `ResolveActorServiceTest`, `BootstrapAdminServiceTest`), контракты (`InMemoryUserRepositoryContractTest`), shared (`PaginationParamsTest`, `ApiExceptionHandlerTest`, `ClockConfigTest`), wiring (`IdentityWiringConfigTest`); Failsafe — IT итерации 0, `IdentityApiIT`, `JpaUserRepositoryContractIT`; `jacoco:check` — LINE ≥ 0.70.

- [ ] **Step 2: Проверить отчёт покрытия**

```bash
ls target/site/jacoco-merged/index.html
```

Expected: файл существует; зафиксировать фактический процент (identity-код покрыт unit/application/контрактными/приёмочными тестами).

- [ ] **Step 3: Merge в main (локально, без push)**

```bash
git checkout main
git merge --no-ff feat/iteration-1-identity -m "merge: итерация 1 — identity"
./mvnw -q verify
```

Expected: `BUILD SUCCESS` на main.

- [ ] **Step 4: Чекпоинт-отчёт**

Сообщить пользователю: что готово (Task 1–9), фактическое покрытие, что отложено (409 при деактивации с активными участи­ями — итерации 5–7; фильтры списка; guest-sessions — итерация 8), ссылка на план следующей итерации (media, `docs/plans/...iteration-2-media.md`). Дождаться review перед итерацией 2.
