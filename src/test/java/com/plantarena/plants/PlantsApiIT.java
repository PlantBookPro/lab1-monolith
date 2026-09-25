package com.plantarena.plants;

import com.jayway.jsonpath.JsonPath;
import com.plantarena.plants.api.PlantEligibility;
import com.plantarena.plants.api.PlantLifecycle;
import com.plantarena.plants.api.PlantModeration;
import com.plantarena.plants.api.PlantNotEligibleException;
import com.plantarena.plants.api.ReservationConflictException;
import com.plantarena.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Внешний цикл TDD итерации 3: сценарии разделов 6 и 13 (растения) и допущения
 * 1–5 раздела 3 через HTTP на Testcontainers. Arrangement (решение модерации,
 * резерв, гибель) — через опубликованный контракт plants.api: приёмочный IT
 * легитимно импортирует несколько контекстов (ContextBoundaryTest исключает
 * тесты). Красный до Task 6.
 */
@DisplayName("Сценарии разделов 6 и 13 (растения): /plants, запреты, резервы, гибель")
class PlantsApiIT extends AbstractIntegrationTest {

    private static final String DEMO_HEADER = "X-Demo-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlantModeration plantModeration;

    @Autowired
    private PlantEligibility plantEligibility;

    @Autowired
    private PlantLifecycle plantLifecycle;

    @Test
    void пользователь_подает_растение_на_свой_файл() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-submit@example.com", "Plants Submit");
        UUID assetId = uploadAs(ownerId, "red-8x8.png");

        String response = mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Мой фикус"}
                    """.formatted(assetId)))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", containsString("/api/v1/plants/")))
            .andExpect(jsonPath("$.ownerId").value(ownerId.toString()))
            .andExpect(jsonPath("$.assetId").value(assetId.toString()))
            .andExpect(jsonPath("$.title").value("Мой фикус"))
            .andExpect(jsonPath("$.moderationStatus").value("PENDING"))
            .andExpect(jsonPath("$.lifeStatus").value("ALIVE"))
            .andExpect(jsonPath("$.createdAt").isNotEmpty())
            .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("fingerprint");
        assertThat(response).doesNotContain("storageKey");
    }

    @Test
    void гость_не_может_подать_растение() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-guest@example.com", "Plants Guest");
        UUID assetId = uploadAs(ownerId, "red-8x8.png");

        mockMvc.perform(post("/api/v1/plants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Чужой фикус"}
                    """.formatted(assetId)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("NOT_IDENTIFIED"));
    }

    @Test
    void чужой_файл_скрыт_при_подаче_растения() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-owner@example.com", "Plants Owner");
        UUID strangerId = createUserAsAdmin("plants-stranger@example.com", "Plants Stranger");
        UUID assetId = uploadAs(ownerId, "red-8x8.png");

        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, strangerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Не мой файл"}
                    """.formatted(assetId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
    }

    @Test
    void несуществующий_файл_не_подается() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-unknown-asset@example.com", "Plants Unknown");

        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"На пустоту"}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
    }

    @Test
    void файл_нельзя_задействовать_вторым_растением() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-reuse@example.com", "Plants Reuse");
        UUID assetId = uploadAs(ownerId, "red-8x8.png");
        submitPlantAs(ownerId, assetId, "Первое растение");

        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Второе растение на тот же файл"}
                    """.formatted(assetId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ASSET_ALREADY_CLAIMED"));
    }

    @Test
    void список_своих_растений_показывает_все_статусы() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-list@example.com", "Plants List");
        UUID first = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Первое");
        UUID second = submitPlantAs(ownerId, uploadAs(ownerId, "green-8x8.png"), "Второе");
        plantModeration.recordDecision(first, PlantModeration.Decision.APPROVED, null);

        mockMvc.perform(get("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total-Count", "2"))
            .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/v1/plants?ownerId=" + ownerId + "&page=0&size=1")
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total-Count", "2"))
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void чужой_список_раскрывает_только_одобренные_растения() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-list-owner@example.com", "Plants List Owner");
        UUID strangerId = createUserAsAdmin("plants-list-stranger@example.com", "Plants List Stranger");
        UUID pending = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Черновик");
        UUID approved = submitPlantAs(ownerId, uploadAs(ownerId, "green-8x8.png"), "Одобренное");
        UUID rejected = submitPlantAs(ownerId, uploadAs(ownerId, "green-8x8.png"), "Отклонённое");
        plantModeration.recordDecision(approved, PlantModeration.Decision.APPROVED, null);
        plantModeration.recordDecision(rejected, PlantModeration.Decision.REJECTED, "не растение");

        mockMvc.perform(get("/api/v1/plants?ownerId=" + ownerId)
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total-Count", "1"))
            .andExpect(jsonPath("$[0].id").value(approved.toString()))
            .andExpect(jsonPath("$[0].moderationStatus").value("APPROVED"));
    }

    @Test
    void чужое_растение_скрыто_до_одобрения() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-hidden@example.com", "Plants Hidden");
        UUID strangerId = createUserAsAdmin("plants-hidden-stranger@example.com", "Plants Hidden Stranger");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Черновик");

        mockMvc.perform(get("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.moderationStatus").value("PENDING"));
    }

    @Test
    void чужое_одобренное_растение_доступно_к_просмотру() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-public@example.com", "Plants Public");
        UUID strangerId = createUserAsAdmin("plants-public-stranger@example.com", "Plants Public Stranger");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Публичное");
        plantModeration.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);

        mockMvc.perform(get("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.moderationStatus").value("APPROVED"));
    }

    @Test
    void владелец_переименовывает_растение_только_по_названию() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-rename@example.com", "Plants Rename");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Старое имя");

        mockMvc.perform(patch("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"Новое имя","assetId":"%s","ownerId":"%s"}
                    """.formatted(UUID.randomUUID(), UUID.randomUUID())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Новое имя"))
            .andExpect(jsonPath("$.assetId").exists());

        mockMvc.perform(get("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(jsonPath("$.title").value("Новое имя"))
            .andExpect(jsonPath("$.lifeStatus").value("ALIVE"));
    }

    @Test
    void чужой_не_переименовывает_растение() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-rename-owner@example.com", "Plants Rename Owner");
        UUID strangerId = createUserAsAdmin("plants-rename-stranger@example.com", "Plants Rename Stranger");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Не трогай");
        plantModeration.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);

        mockMvc.perform(patch("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, strangerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"Взлом"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void владелец_архивирует_растение() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-archive@example.com", "Plants Archive");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "На архив");

        mockMvc.perform(delete("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isNotFound());
    }

    @Test
    void архивация_растения_под_активным_резервом_запрещена() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-reserved@example.com", "Plants Reserved");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "В турнире");
        UUID reservationId = plantEligibility.reserveSubmission(ownerId, plantId, UUID.randomUUID());

        mockMvc.perform(delete("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PLANT_UNDER_RESERVATION"));

        plantEligibility.releaseReservation(reservationId);

        mockMvc.perform(delete("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isNoContent());
    }

    @Test
    void статус_модерации_доступен_владельцу() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-moderation@example.com", "Plants Moderation");
        UUID strangerId = createUserAsAdmin("plants-moderation-stranger@example.com", "Plants Moderation Stranger");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "На модерации");

        mockMvc.perform(get("/api/v1/plants/" + plantId + "/moderation")
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.moderationStatus").value("PENDING"))
            .andExpect(jsonPath("$.retryUploadAllowed").value(false));

        plantModeration.recordDecision(plantId, PlantModeration.Decision.REJECTED, "не растение");

        mockMvc.perform(get("/api/v1/plants/" + plantId + "/moderation")
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.moderationStatus").value("REJECTED"))
            .andExpect(jsonPath("$.reason").value("не растение"))
            .andExpect(jsonPath("$.retryUploadAllowed").value(true));

        mockMvc.perform(get("/api/v1/plants/" + plantId + "/moderation")
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));
    }

    @Test
    void одобрение_раскрывает_файл_растения_другим_пользователям() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-file-public@example.com", "Plants File Public");
        UUID strangerId = createUserAsAdmin("plants-file-stranger@example.com", "Plants File Stranger");
        UUID assetId = uploadAs(ownerId, "red-8x8.png");
        UUID plantId = submitPlantAs(ownerId, assetId, "Заявленное");

        mockMvc.perform(get("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("MEDIA_ASSET_NOT_FOUND"));

        plantModeration.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);

        mockMvc.perform(get("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, strangerId.toString()))
            .andExpect(status().isOk());
    }

    @Test
    void удаление_файла_задействованного_растением_запрещено() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-file-delete@example.com", "Plants File Delete");
        UUID assetId = uploadAs(ownerId, "red-8x8.png");
        UUID plantId = submitPlantAs(ownerId, assetId, "Задействованное");

        mockMvc.perform(delete("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ASSET_IN_USE"));

        mockMvc.perform(delete("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/files/" + assetId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isNoContent());
    }

    @Test
    void погибшее_растение_не_воскресает_повторная_загрузка_создаёт_новый_экземпляр() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-death@example.com", "Plants Death");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Первенец");
        plantModeration.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);
        plantLifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.PERMANENT, null,
            "поражение в закрытом турнире", UUID.randomUUID());

        mockMvc.perform(get("/api/v1/plants/" + plantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lifeStatus").value("DEAD"))
            .andExpect(jsonPath("$.diedAt").isNotEmpty());

        UUID newPlantId = submitPlantAs(ownerId, uploadAs(ownerId, "green-8x8.png"), "Второй экземпляр");

        assertThat(newPlantId).isNotEqualTo(plantId);
        mockMvc.perform(get("/api/v1/plants/" + newPlantId)
                .header(DEMO_HEADER, ownerId.toString()))
            .andExpect(jsonPath("$.lifeStatus").value("ALIVE"));
    }

    @Test
    void постоянный_запрет_блокирует_ту_же_картинку_навсегда() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-permanent@example.com", "Plants Permanent");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Погибшее");
        plantLifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.PERMANENT, null,
            "поражение в закрытом турнире", UUID.randomUUID());

        UUID sameImageAsset = uploadAs(ownerId, "red-8x8.png"); // новые байты загрузятся — новый asset

        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Та же картинка"}
                    """.formatted(sameImageAsset)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IMAGE_RESTRICTED"))
            .andExpect(jsonPath("$.retryAt").isEmpty());
    }

    @Test
    void суточный_запрет_блокирует_совпавшую_картинку_до_истечения() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-cooldown@example.com", "Plants Cooldown");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Глобальное поражение");
        plantLifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.COOLDOWN,
            Instant.now().plus(Duration.ofHours(24)), "поражение в глобальном турнире", UUID.randomUUID());

        UUID sameImageAsset = uploadAs(ownerId, "red-8x8.png");

        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Та же картинка"}
                    """.formatted(sameImageAsset)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IMAGE_RESTRICTED"))
            .andExpect(jsonPath("$.retryAt").isNotEmpty());
    }

    @Test
    void постоянный_запрет_имеет_приоритет_над_временным() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-priority@example.com", "Plants Priority");
        UUID first = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Сначала глобальное");
        UUID second = submitPlantAs(ownerId, uploadAs(ownerId, "green-8x8.png"), "Потом закрытое");
        plantLifecycle.registerDeath(first, PlantLifecycle.RestrictionKind.COOLDOWN,
            Instant.now().plus(Duration.ofHours(24)), "поражение в глобальном турнире", UUID.randomUUID());
        plantLifecycle.registerDeath(second, PlantLifecycle.RestrictionKind.PERMANENT, null,
            "поражение в закрытом турнире", UUID.randomUUID());

        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Снова та же картинка"}
                    """.formatted(uploadAs(ownerId, "green-8x8.png"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IMAGE_RESTRICTED"))
            .andExpect(jsonPath("$.retryAt").isEmpty()); // действует постоянный запрет, не суточный
    }

    @Test
    void чужое_поражение_не_блокирует_фотографию_у_всех() throws Exception {
        UUID firstOwner = createUserAsAdmin("plants-restriction-a@example.com", "Plants Restriction A");
        UUID secondOwner = createUserAsAdmin("plants-restriction-b@example.com", "Plants Restriction B");
        UUID plantId = submitPlantAs(firstOwner, uploadAs(firstOwner, "red-8x8.png"), "Погибшее у A");
        plantLifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.PERMANENT, null,
            "поражение в закрытом турнире", UUID.randomUUID());

        UUID sameImageAsset = uploadAs(secondOwner, "red-8x8.png"); // те же пиксели, другой владелец

        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, secondOwner.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Та же картинка у другого владельца"}
                    """.formatted(sameImageAsset)))
            .andExpect(status().isCreated());
    }

    @Test
    void суточный_запрет_касается_только_совпавшей_картинки() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-other-image@example.com", "Plants Other Image");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Глобальное поражение");
        plantLifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.COOLDOWN,
            Instant.now().plus(Duration.ofHours(24)), "поражение в глобальном турнире", UUID.randomUUID());

        mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, ownerId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"Другая картинка сразу после гибели"}
                    """.formatted(uploadAs(ownerId, "green-8x8.png"))))
            .andExpect(status().isCreated());
    }

    @Test
    void резерв_изображения_идемпотентен_по_ключу_и_одиночен_на_пару() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-reserve@example.com", "Plants Reserve");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Для резерва");
        UUID key = UUID.randomUUID();

        UUID first = plantEligibility.reserveSubmission(ownerId, plantId, key);
        UUID retry = plantEligibility.reserveSubmission(ownerId, plantId, key);

        assertThat(retry).isEqualTo(first); // тот же ключ — тот же reservationId

        assertThatThrownBy(() -> plantEligibility.reserveSubmission(ownerId, plantId, UUID.randomUUID()))
            .isInstanceOf(ReservationConflictException.class); // другой ключ — конфликт (допущение 4)
    }

    @Test
    void подтверждение_допуска_требует_одобрения_и_подтверждает_резерв() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-confirm@example.com", "Plants Confirm");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "На допуск");
        UUID reservationId = plantEligibility.reserveSubmission(ownerId, plantId, UUID.randomUUID());

        assertThatThrownBy(() -> plantEligibility.confirmEligibility(ownerId, plantId, reservationId))
            .isInstanceOf(PlantNotEligibleException.class)
            .extracting("reason")
            .isEqualTo(PlantNotEligibleException.Reason.NOT_APPROVED); // PENDING не допускается к старту

        plantModeration.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);
        plantEligibility.confirmEligibility(ownerId, plantId, reservationId); // без исключения

        plantEligibility.releaseReservation(reservationId);
        UUID renewed = plantEligibility.reserveSubmission(ownerId, plantId, UUID.randomUUID());

        assertThat(renewed).isNotEqualTo(reservationId); // после освобождения — новый резерв
    }

    @Test
    void повторная_гибель_идемпотентна_и_не_дублирует_запрет() throws Exception {
        UUID ownerId = createUserAsAdmin("plants-death-idem@example.com", "Plants Death Idem");
        UUID plantId = submitPlantAs(ownerId, uploadAs(ownerId, "red-8x8.png"), "Одна гибель");
        String reason = "поражение в закрытом турнире";

        plantLifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.PERMANENT, null, reason, null);
        plantLifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.PERMANENT, null, reason, null);

        Integer restrictions = jdbcTemplate.queryForObject(
            "select count(*) from plants.image_restriction where owner_id = ?", Integer.class, ownerId);
        assertThat(restrictions).isEqualTo(1);
    }

    // ---------- helpers ----------

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

    private UUID uploadAs(UUID userId, String referenceName) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/files")
                .file(new MockMultipartFile("file", referenceName, MediaType.IMAGE_PNG_VALUE,
                    referenceBytes(referenceName)))
                .header(DEMO_HEADER, userId.toString()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private UUID submitPlantAs(UUID userId, UUID assetId, String title) throws Exception {
        String response = mockMvc.perform(post("/api/v1/plants")
                .header(DEMO_HEADER, userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"assetId":"%s","title":"%s"}
                    """.formatted(assetId, title)))
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
}
