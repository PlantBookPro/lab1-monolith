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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
        submitGlobalEntry(u2, plant2);

        // эпоха открыта; u1 побеждает (голос), u2 выбывает
        runDue();
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> mockMvc.perform(get("/api/v1/me/global-entry")
                    .header(DEMO_HEADER, u1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUALIFYING")));
        String clusters = mockMvc.perform(get("/api/v1/global/clusters"))
            .andReturn().getResponse().getContentAsString();
        UUID windowId = UUID.fromString((String) ((java.util.Map<String, Object>)
            ((List<?>) JsonPath.read(clusters, "$")).get(0)).get("windowId"));
        mockMvc.perform(put("/api/v1/windows/" + windowId + "/entries/" + entry1 + "/vote")
                .header(DEMO_HEADER, u2.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"LIKE\"}"))
            .andExpect(status().isOk());
        // u2 не может голосовать за себя; голосует за entry1 — не обязательно
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> mockMvc.perform(get("/api/v1/plants/" + plant2)
                    .header(DEMO_HEADER, u2.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifeStatus").value("DEAD")));

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
        assertThat((Object) JsonPath.read(finalWindowAfter, "$.windowId"))
            .isEqualTo(JsonPath.read(finalWindowBefore, "$.windowId")); // финал не пересоздан
    }

    @Test
    @DisplayName("конкурентная подача одного пользователя: ровно одно 201, остальные 409 (раздел 11)")
    void конкурентная_подача() throws Exception {
        UUID user = createUserAsAdmin("idem-race@example.com", "Race");
        setLocation(user, 55.7558, 37.6173);
        List<UUID> plants = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            plants.add(approvedPlantOf(user, "Гонка " + i));
        }

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
            int statusCode = result.get(30, TimeUnit.SECONDS);
            if (statusCode == 201) {
                created++;
            } else if (statusCode == 409) {
                conflicts++;
            }
        }
        executor.shutdown();

        assertThat((long) created).isEqualTo(1);
        assertThat((long) conflicts).isEqualTo(threads - 1);
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
                .andExpect(jsonPath("$.moderationStatus").value("APPROVED")));
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
