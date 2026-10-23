package com.plantarena.plants.adapter.in.web;

import com.plantarena.plants.api.ModerationAlreadyDecidedException;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.application.AssetAlreadyClaimedException;
import com.plantarena.plants.application.AssetNotFoundException;
import com.plantarena.plants.application.ImageRestrictedException;
import com.plantarena.plants.application.PlantUnderReservationException;
import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.shared.web.ApiError;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Ошибки plants: 404 скрытые, 409 конфликты задействованности и запретов")
class PlantExceptionHandlerTest {

    private final PlantExceptionHandler handler = new PlantExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/plants");

    @Test
    void скрытое_растение_даёт_404_plant_not_found() {
        ResponseEntity<ApiError> response = handler.plantNotFound(
            new PlantNotFoundException("Растение не найдено"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("PLANT_NOT_FOUND");
    }

    @Test
    void чужой_файл_даёт_404_asset_not_found() {
        ResponseEntity<ApiError> response = handler.assetNotFound(
            new AssetNotFoundException("Файл не найден"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("ASSET_NOT_FOUND");
    }

    @Test
    void занятый_файл_даёт_409_asset_already_claimed() {
        ResponseEntity<ApiError> response = handler.alreadyClaimed(
            new AssetAlreadyClaimedException("Файл уже задействован"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("ASSET_ALREADY_CLAIMED");
    }

    @Test
    void постоянный_запрет_даёт_409_image_restricted_без_срока() {
        ImageRestrictedException exception = new ImageRestrictedException(
            ImageRestriction.permanent(UUID.randomUUID(),
                new ImageFingerprint("a".repeat(64), 1),
                "поражение в закрытом турнире", null,
                Instant.parse("2026-09-25T10:00:00Z")));

        ResponseEntity<ApiError> response = handler.imageRestricted(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("IMAGE_RESTRICTED");
        assertThat(response.getBody().retryAt()).isNull(); // PERMANENT — повтора не будет
    }

    @Test
    void суточный_запрет_даёт_409_со_сроком_повтора() {
        ImageRestrictedException exception = new ImageRestrictedException(
            ImageRestriction.cooldown(UUID.randomUUID(),
                new ImageFingerprint("b".repeat(64), 1),
                "поражение в глобальном турнире", null,
                Instant.parse("2026-09-26T10:00:00Z"),
                Instant.parse("2026-09-25T10:00:00Z")));

        ResponseEntity<ApiError> response = handler.imageRestricted(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("IMAGE_RESTRICTED");
        assertThat(response.getBody().retryAt())
            .isEqualTo(Instant.parse("2026-09-26T10:00:00Z")); // COOLDOWN — срок есть
    }

    @Test
    void резерв_и_решённая_модерация_дают_409() {
        ResponseEntity<ApiError> reserved = handler.underReservation(
            new PlantUnderReservationException("Растение под активным резервом"), request);
        ResponseEntity<ApiError> decided = handler.alreadyDecided(
            new ModerationAlreadyDecidedException("Решение уже зафиксировано"), request);

        assertThat(reserved.getStatusCode().value()).isEqualTo(409);
        assertThat(reserved.getBody().code()).isEqualTo("PLANT_UNDER_RESERVATION");
        assertThat(decided.getStatusCode().value()).isEqualTo(409);
        assertThat(decided.getBody().code()).isEqualTo("MODERATION_ALREADY_DECIDED");
    }
}
