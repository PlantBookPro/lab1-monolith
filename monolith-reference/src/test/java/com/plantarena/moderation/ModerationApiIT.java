package com.plantarena.moderation;

import com.jayway.jsonpath.JsonPath;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import com.plantarena.moderation.support.DeterministicPlantClassifier;
import com.plantarena.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Внешний цикл TDD итерации 4: сквозной сценарий «загрузка → заявка → задание
 * → решение» через HTTP на Testcontainers (раздел 6). Классификатор
 * детерминированный (@TestConfiguration): зелёный PNG → APPROVED, красный →
 * REJECTED. Обработку выполняет @Scheduled-poller (2с) — ждём Awaitility.
 * Красный до Task 6 (задания никто не создаёт и не обрабатывает).
 */
@DisplayName("Сценарии раздела 6 (модерация): заявка → задание → решение (moderation)")
class ModerationApiIT extends AbstractIntegrationTest {

    private static final String DEMO_HEADER = "X-Demo-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Детерминированный классификатор вместо ONNX (спека итерации 4). */
    @TestConfiguration
    static class DeterministicClassifierConfig {

        @Bean
        @Primary
        PlantClassifier deterministicPlantClassifier() {
            return new DeterministicPlantClassifier();
        }
    }

    @Test
    @DisplayName("зелёное изображение одобрено: причина, файл публично виден чужим")
    void зелёное_изображение_одобрено_файл_публичен_чужим() throws Exception {
        UUID ownerId = createUserAsAdmin("mod-green@example.com", "Mod Green");
        UUID strangerId = createUserAsAdmin("mod-green-stranger@example.com", "Mod Green Stranger");
        UUID assetId = uploadAs(ownerId, referenceBytes("green-8x8.png"));
        UUID plantId = submitPlant(ownerId, assetId, "Мой фикус");

        awaitModeration(ownerId, plantId, "APPROVED");

        mockMvc.perform(get("/api/v1/plants/" + plantId + "/moderation")
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.moderationStatus").value("APPROVED"))
            .andExpect(jsonPath("$.reason").value("PLANT_DETECTED"))
            .andExpect(jsonPath("$.retryUploadAllowed").value(false));

        // одобренное растение и его файл публичны (ADR-008)
        mockMvc.perform(get("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.moderationStatus").value("APPROVED"));
        mockMvc.perform(get("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("не-растение отклонено: причина NOT_A_PLANT, разрешён повторный upload")
    void не_растение_отклонено_причина_и_повторный_upload() throws Exception {
        UUID ownerId = createUserAsAdmin("mod-red@example.com", "Mod Red");
        UUID strangerId = createUserAsAdmin("mod-red-stranger@example.com", "Mod Red Stranger");
        UUID assetId = uploadAs(ownerId, referenceBytes("red-8x8.png"));
        UUID plantId = submitPlant(ownerId, assetId, "Кот в горшке");

        awaitModeration(ownerId, plantId, "REJECTED");

        mockMvc.perform(get("/api/v1/plants/" + plantId + "/moderation")
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.moderationStatus").value("REJECTED"))
            .andExpect(jsonPath("$.reason").value("NOT_A_PLANT"))
            .andExpect(jsonPath("$.retryUploadAllowed").value(true));

        // отклонённый файл не публичен: чужой получает 404 (ADR-008)
        mockMvc.perform(get("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isNotFound());
    }

    /** Ждём решения poller'а (fixedDelay 2с): PENDING → APPROVED/REJECTED. */
    private void awaitModeration(UUID ownerId, UUID plantId, String expectedStatus) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> mockMvc.perform(
                    get("/api/v1/plants/" + plantId + "/moderation")
                        .header(DEMO_HEADER, ownerId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value(expectedStatus)));
    }

    private UUID submitPlant(UUID ownerId, UUID assetId, String title) throws Exception {
        String response = mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assetId\":\"%s\",\"title\":\"%s\"}".formatted(assetId, title)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
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
