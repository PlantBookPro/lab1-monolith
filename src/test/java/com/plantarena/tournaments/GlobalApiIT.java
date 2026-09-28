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
        Object beforeWindowId = JsonPath.read(before, "$.windowId");
        runDue();
        runDue();
        String after = mockMvc.perform(get("/api/v1/global/leaderboard?scope=FINAL"))
            .andReturn().getResponse().getContentAsString();
        Object afterWindowId = JsonPath.read(after, "$.windowId");
        assertThat(afterWindowId).isEqualTo(beforeWindowId);
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
