package com.plantarena.feed;

import com.jayway.jsonpath.JsonPath;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.support.DeterministicPlantClassifier;
import com.plantarena.support.AbstractIntegrationTest;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
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

    /**
     * Свежий мир на каждый тест (раздел 9): глобальный турнир — синглтон, и
     * открытая эпоха (PT2M) не даст следующему тесту открыть свою — чистим
     * мир турниров/проекции целиком, как контрактные IT (урок итерации 2).
     */
    @BeforeEach
    void очистить_мир_турниров() {
        jdbcTemplate.update("delete from feed.feed_card");
        jdbcTemplate.update("delete from tournaments.vote");
        jdbcTemplate.update("delete from tournaments.window_participant");
        jdbcTemplate.update("delete from tournaments.voting_window");
        jdbcTemplate.update("delete from geo.cluster_member");
        jdbcTemplate.update("delete from geo.cluster_snapshot");
        jdbcTemplate.update("delete from tournaments.qualification_epoch");
        jdbcTemplate.update("delete from tournaments.tournament_tag");
        jdbcTemplate.update("delete from tournaments.invitation");
        jdbcTemplate.update("delete from tournaments.tournament_entry");
        // глобальный турнир — синглтон (bootstrap): строка остаётся, мир вокруг чист
        jdbcTemplate.update("delete from tournaments.tournament where id <> '"
            + "00000007-10ba-4000-8000-000000000001'");
        jdbcTemplate.update("delete from tournaments.tag");
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

        // TTL 2 секунды истёк: pollDelay держит ожидание минимум 3 секунды
        Awaitility.await().pollDelay(Duration.ofSeconds(3)).until(() -> true);

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

    /** Зритель с двумя глобальными карточками в ленте (для limit=1 → hasNext). */
    private UUID newUserWithGlobalCard() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID u1 = newUser("cursor-u1-" + suffix + "@example.com", "Cursor1");
        UUID u2 = newUser("cursor-u2-" + suffix + "@example.com", "Cursor2");
        putLocation(u1);
        putLocation(u2);
        // растения пре-апрувятся до подач: иначе шедулер границ (fixedDelay 2с)
        // откроет эпоху между подачами — и вторая заявка застрянет в QUEUED
        UUID p1 = approvedPlantOf(u1);
        UUID p2 = approvedPlantOf(u2);
        submitGlobal(u1, p1);
        submitGlobal(u2, p2);
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                runDue();
                mockMvc.perform(get("/api/v1/feed").header(DEMO_HEADER, adminId().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(2));
            });
        // зритель без своих карточек: своя карточка из ленты исключается (раздел 9)
        return newUser("cursor-viewer-" + suffix + "@example.com", "Viewer");
    }

    private void putLocation(UUID user) throws Exception {
        mockMvc.perform(put("/api/v1/me/location")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":%s,\"longitude\":%s}".formatted(LAT_MOSCOW, LON_MOSCOW)))
            .andExpect(status().isOk());
    }

    private void submitGlobal(UUID user, UUID plantId) throws Exception {
        mockMvc.perform(post("/api/v1/global/entries")
                .header(DEMO_HEADER, user.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantId\":\"%s\"}".formatted(plantId)))
            .andExpect(status().isCreated());
    }

    private UUID approvedPlantOf(UUID user) throws Exception {
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
        return plantId;
    }

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
