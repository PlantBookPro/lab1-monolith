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
            .andExpect(jsonPath("$[?(@.userId=='%s')].status".formatted(u1)).value("ELIMINATED"))
            .andExpect(jsonPath("$[?(@.userId=='%s')].status".formatted(u2)).value("ACTIVE"));

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
