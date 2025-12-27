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

    @Test
    void некорректный_параметр_пагинации_типа_отклоняется() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                .header(DEMO_HEADER, adminId().toString()).param("page", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
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
