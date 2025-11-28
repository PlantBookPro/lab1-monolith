package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantEligibility;
import com.plantarena.plants.api.PlantLifecycle;
import com.plantarena.plants.api.PlantNotEligibleException;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.ReservationConflictException;
import com.plantarena.plants.application.support.FakeIntegrationEventPublisher;
import com.plantarena.plants.application.support.InMemoryImageRestrictionRepository;
import com.plantarena.plants.application.support.InMemoryPlantRepository;
import com.plantarena.plants.application.support.InMemoryPlantReservationRepository;
import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Контракт PlantEligibility: резерв, подтверждение допуска, освобождение")
class PlantEligibilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final ImageFingerprint FINGERPRINT =
        new ImageFingerprint("4".repeat(64), 1);

    private final PlantRepository plants = new InMemoryPlantRepository();
    private final InMemoryImageRestrictionRepository restrictions =
        new InMemoryImageRestrictionRepository();
    private final InMemoryPlantReservationRepository reservations =
        new InMemoryPlantReservationRepository();
    private final PlantEligibilityService service = new PlantEligibilityService(
        plants, restrictions, reservations, Clock.fixed(NOW, ZoneOffset.UTC));
    private final PlantLifecycleService lifecycle = new PlantLifecycleService(
        plants, restrictions, new FakeIntegrationEventPublisher(),
        Clock.fixed(NOW, ZoneOffset.UTC));

    private UUID submittedPlant(UUID ownerId) {
        Plant plant = Plant.submit(ownerId, UUID.randomUUID(), FINGERPRINT, "Фикус", NOW);
        plants.save(plant);
        return plant.id();
    }

    @Test
    void резерв_проверяет_владельца_жизнь_статус_и_запреты() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId);

        assertThatThrownBy(() -> service.reserveSubmission(UUID.randomUUID(), plantId,
            UUID.randomUUID()))
            .isInstanceOf(PlantNotEligibleException.class)
            .extracting("reason")
            .isEqualTo(PlantNotEligibleException.Reason.NOT_OWNER);

        assertThatThrownBy(() -> service.reserveSubmission(ownerId, UUID.randomUUID(),
            UUID.randomUUID()))
            .isInstanceOf(PlantNotFoundException.class);
    }

    @Test
    void отклонённая_заявка_не_резервируется() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId);
        plants.findById(plantId).ifPresent(plant -> {
            plant.applyDecision(ModerationStatus.REJECTED, "не растение");
            plants.save(plant);
        });

        assertThatThrownBy(() -> service.reserveSubmission(ownerId, plantId, UUID.randomUUID()))
            .isInstanceOf(PlantNotEligibleException.class)
            .extracting("reason")
            .isEqualTo(PlantNotEligibleException.Reason.NOT_RESERVABLE);
    }

    @Test
    void запрещённое_изображение_не_резервируется_с_указанием_срока() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId);
        lifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.COOLDOWN,
            NOW.plus(Duration.ofHours(24)), "поражение в глобальном турнире", null);
        UUID secondPlantId = submittedPlant(ownerId); // та же картинка, новое растение

        assertThatThrownBy(() -> service.reserveSubmission(ownerId, secondPlantId,
            UUID.randomUUID()))
            .isInstanceOf(PlantNotEligibleException.class)
            .extracting("reason")
            .isEqualTo(PlantNotEligibleException.Reason.RESTRICTED);
    }

    @Test
    void одно_изображение_владельца_не_участвует_одновременно_в_нескольких_турнирах() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId);
        UUID first = service.reserveSubmission(ownerId, plantId, UUID.randomUUID());

        assertThatThrownBy(() -> service.reserveSubmission(ownerId, plantId, UUID.randomUUID()))
            .isInstanceOf(ReservationConflictException.class); // допущение 4

        UUID retry = service.reserveSubmission(ownerId, plantId, reservations
            .findById(first).orElseThrow().idempotencyKey());
        assertThat(retry).isEqualTo(first); // тот же ключ — тот же reservationId
    }

    @Test
    void подтверждение_допуска_требует_одобрения_и_активный_резерв() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId);
        UUID reservationId = service.reserveSubmission(ownerId, plantId, UUID.randomUUID());

        assertThatThrownBy(() -> service.confirmEligibility(ownerId, plantId, reservationId))
            .isInstanceOf(PlantNotEligibleException.class)
            .extracting("reason")
            .isEqualTo(PlantNotEligibleException.Reason.NOT_APPROVED); // PENDING

        plants.findById(plantId).ifPresent(plant -> {
            plant.applyDecision(ModerationStatus.APPROVED, null);
            plants.save(plant);
        });
        service.confirmEligibility(ownerId, plantId, reservationId); // без исключения

        service.releaseReservation(reservationId);
        assertThatThrownBy(() -> service.confirmEligibility(ownerId, plantId, reservationId))
            .isInstanceOf(PlantNotEligibleException.class)
            .extracting("reason")
            .isEqualTo(PlantNotEligibleException.Reason.NO_ACTIVE_RESERVATION);

        UUID renewed = service.reserveSubmission(ownerId, plantId, UUID.randomUUID());
        assertThat(renewed).isNotEqualTo(reservationId); // после освобождения — новый резерв
    }

    @Test
    void освобождение_идемпотентно() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId);
        UUID reservationId = service.reserveSubmission(ownerId, plantId, UUID.randomUUID());

        service.releaseReservation(reservationId);
        service.releaseReservation(reservationId); // без исключения
        service.releaseReservation(UUID.randomUUID()); // несуществующий — no-op
    }
}
