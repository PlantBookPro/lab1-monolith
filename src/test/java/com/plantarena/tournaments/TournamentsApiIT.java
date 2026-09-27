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
