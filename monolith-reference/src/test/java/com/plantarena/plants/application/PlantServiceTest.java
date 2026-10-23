package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.event.PlantSubmittedEvent;
import com.plantarena.plants.application.port.in.SubmitPlantUseCase;
import com.plantarena.plants.application.support.FakeIntegrationEventPublisher;
import com.plantarena.plants.application.support.FakeMediaAssetClaims;
import com.plantarena.plants.application.support.FakeMediaAssets;
import com.plantarena.plants.application.support.InMemoryImageRestrictionRepository;
import com.plantarena.plants.application.support.InMemoryPlantRepository;
import com.plantarena.plants.application.support.InMemoryPlantReservationRepository;
import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import com.plantarena.plants.domain.PlantReservation;
import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Use cases растений: подача, видимость, архивация, запреты")
class PlantServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final String FINGERPRINT = "1".repeat(64);
    private static final String OTHER_FINGERPRINT = "2".repeat(64);

    private final PlantRepository plants = new InMemoryPlantRepository();
    private final InMemoryImageRestrictionRepository restrictions =
        new InMemoryImageRestrictionRepository();
    private final InMemoryPlantReservationRepository reservations =
        new InMemoryPlantReservationRepository();
    private final FakeMediaAssets mediaAssets = new FakeMediaAssets();
    private final FakeMediaAssetClaims mediaClaims = new FakeMediaAssetClaims();
    private final FakeIntegrationEventPublisher events = new FakeIntegrationEventPublisher();
    private final PlantService service = new PlantService(plants, restrictions, reservations,
        mediaAssets, mediaClaims, new PlantsAccessPolicy(), events,
        Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID ownerId = UUID.randomUUID();
    private final CurrentActor owner =
        CurrentActor.identified(ownerId, Set.of(AppRole.USER));
    private final CurrentActor stranger =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER));
    private final CurrentActor admin =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER, AppRole.ADMIN));

    private UUID assetOf(UUID ownerId, String fingerprint) {
        UUID assetId = UUID.randomUUID();
        mediaAssets.addAsset(assetId, ownerId, fingerprint);
        return assetId;
    }

    private UUID submittedPlant() {
        return service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetOf(ownerId, FINGERPRINT), "Фикус")).id();
    }

    @Test
    void подача_создаёт_PENDING_растение_занимает_файл_и_публикует_событие() {
        UUID assetId = assetOf(ownerId, FINGERPRINT);

        var result = service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetId, "Мой фикус"));

        assertThat(result.moderationStatus().name()).isEqualTo("PENDING");
        assertThat(mediaClaims.claimedAssets).containsExactly(assetId);
        assertThat(mediaClaims.publicAssets).isEmpty(); // занят, но не публичен
        assertThat(events.published).hasSize(1);
        PlantSubmittedEvent event = (PlantSubmittedEvent) events.published.get(0);
        assertThat(event.eventType()).isEqualTo("PlantSubmitted");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.aggregateId()).isEqualTo(result.id());
        assertThat(event.payload().fingerprint()).isEqualTo(FINGERPRINT);
        assertThat(event.payload().ownerId()).isEqualTo(ownerId);
    }

    @Test
    void гость_не_подает_растение() {
        assertThatThrownBy(() -> service.submit(CurrentActor.guest(),
            new SubmitPlantUseCase.SubmitPlantCommand(UUID.randomUUID(), "Фикус")))
            .isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    void чужой_и_несуществующий_файл_скрыты() {
        assertThatThrownBy(() -> service.submit(stranger,
            new SubmitPlantUseCase.SubmitPlantCommand(assetOf(ownerId, FINGERPRINT), "Чужое")))
            .isInstanceOf(AssetNotFoundException.class);
        assertThatThrownBy(() -> service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(UUID.randomUUID(), "Ничьё")))
            .isInstanceOf(AssetNotFoundException.class);
    }

    @Test
    void файл_нельзя_задействовать_вторым_растением() {
        UUID assetId = assetOf(ownerId, FINGERPRINT);
        service.submit(owner, new SubmitPlantUseCase.SubmitPlantCommand(assetId, "Первое"));

        assertThatThrownBy(() -> service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetId, "Второе")))
            .isInstanceOf(AssetAlreadyClaimedException.class);
    }

    @Test
    void суточный_запрет_блокирует_совпавшую_картинку_до_истечения() {
        restrictions.save(ImageRestriction.cooldown(ownerId,
            new ImageFingerprint(FINGERPRINT, 1),
            "поражение в глобальном турнире", null, NOW.plus(Duration.ofHours(24)),
            NOW.minusSeconds(1)));

        assertThatThrownBy(() -> service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetOf(ownerId, FINGERPRINT), "Рано")))
            .isInstanceOf(ImageRestrictedException.class)
            .extracting("retryAt")
            .isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void истёкший_суточный_запрет_не_блокирует() {
        restrictions.save(ImageRestriction.cooldown(ownerId,
            new ImageFingerprint(FINGERPRINT, 1),
            "поражение в глобальном турнире", null, NOW.minusSeconds(1), NOW.minusSeconds(2)));

        var result = service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetOf(ownerId, FINGERPRINT), "Можно"));

        assertThat(result.id()).isNotNull();
    }

    @Test
    void суточный_запрет_касается_только_совпавшей_картинки() {
        restrictions.save(ImageRestriction.cooldown(ownerId,
            new ImageFingerprint(FINGERPRINT, 1),
            "поражение в глобальном турнире", null, NOW.plus(Duration.ofHours(24)),
            NOW.minusSeconds(1)));

        var result = service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetOf(ownerId, OTHER_FINGERPRINT),
                "Другая картинка сразу после гибели"));

        assertThat(result.id()).isNotNull(); // допущение 5: не вся учётная запись
    }

    @Test
    void чужое_поражение_не_блокирует_фотографию_у_всех() {
        UUID otherOwner = UUID.randomUUID();
        restrictions.save(ImageRestriction.permanent(otherOwner,
            new ImageFingerprint(FINGERPRINT, 1),
            "поражение в закрытом турнире", null, NOW.minusSeconds(1)));

        var result = service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetOf(ownerId, FINGERPRINT), "Моё такое же"));

        assertThat(result.id()).isNotNull(); // допущение 3: область запрета — пара (ownerId, fingerprint)
    }

    @Test
    void список_чужих_раскрывает_только_одобренные() {
        UUID pending = submittedPlant();
        UUID approved = service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetOf(ownerId, OTHER_FINGERPRINT),
                "Одобренное")).id();
        plants.findById(approved).ifPresent(plant -> {
            plant.applyDecision(ModerationStatus.APPROVED, null);
            plants.save(plant);
        });

        var strangerView = service.list(stranger, ownerId, 0, 20);
        var ownView = service.list(owner, ownerId, 0, 20);
        var adminView = service.list(admin, ownerId, 0, 20);

        assertThat(strangerView.total()).isEqualTo(1);
        assertThat(strangerView.items().get(0).id()).isEqualTo(approved);
        assertThat(ownView.total()).isEqualTo(2);
        assertThat(adminView.total()).isEqualTo(2);
        assertThat(pending).isNotNull();
    }

    @Test
    void просмотр_скрытых_и_архивированных_растений_404() {
        UUID plantId = submittedPlant();
        Plant plant = plants.findById(plantId).orElseThrow();
        plant.archive(NOW);
        plants.save(plant);

        assertThatThrownBy(() -> service.get(owner, plantId))
            .isInstanceOf(PlantNotFoundException.class);
        assertThatThrownBy(() -> service.get(stranger, plantId))
            .isInstanceOf(PlantNotFoundException.class);
    }

    @Test
    void переименование_и_архивация_только_владельцем() {
        UUID plantId = submittedPlant();

        assertThatThrownBy(() -> service.rename(stranger, plantId, "Взлом"))
            .isInstanceOf(PlantNotFoundException.class); // PENDING скрыт от чужих
        assertThatThrownBy(() -> service.archive(stranger, plantId))
            .isInstanceOf(PlantNotFoundException.class);

        Plant approved = plants.findById(plantId).orElseThrow();
        approved.applyDecision(ModerationStatus.APPROVED, null);
        plants.save(approved);

        assertThatThrownBy(() -> service.rename(stranger, plantId, "Взлом"))
            .isInstanceOf(AccessDeniedException.class); // видимое, но не своё
        assertThatThrownBy(() -> service.archive(admin, plantId))
            .isInstanceOf(AccessDeniedException.class); // админ не владелец (раздел 13)

        assertThat(service.rename(owner, plantId, "Новое имя").title()).isEqualTo("Новое имя");
    }

    @Test
    void архивация_освобождает_файл_и_запрещена_под_резервом() {
        UUID assetId = assetOf(ownerId, FINGERPRINT);
        UUID plantId = service.submit(owner,
            new SubmitPlantUseCase.SubmitPlantCommand(assetId, "На архив")).id();
        reservations.save(PlantReservation.reserve(
            ownerId, plantId, new ImageFingerprint(FINGERPRINT, 1),
            UUID.randomUUID(), NOW));

        assertThatThrownBy(() -> service.archive(owner, plantId))
            .isInstanceOf(PlantUnderReservationException.class);

        reservations.findByIdempotencyKey(reservations.sorted().get(0).idempotencyKey())
            .ifPresent(reservation -> reservations.save(reservation.release(NOW) ? reservation
                : reservation));

        service.archive(owner, plantId);
        assertThat(mediaClaims.claimedAssets).isEmpty(); // файл освобождён
    }

    @Test
    void статус_модерации_только_владельцу() {
        UUID plantId = submittedPlant();

        assertThatThrownBy(() -> service.moderation(stranger, plantId))
            .isInstanceOf(PlantNotFoundException.class);

        var result = service.moderation(owner, plantId);
        assertThat(result.moderationStatus().name()).isEqualTo("PENDING");
        assertThat(result.retryUploadAllowed()).isFalse();
    }
}
