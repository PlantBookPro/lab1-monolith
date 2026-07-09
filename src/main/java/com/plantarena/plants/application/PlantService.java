package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantData;
import com.plantarena.plants.api.PlantLifeStatus;
import com.plantarena.plants.api.PlantModerationStatus;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.event.PlantSubmittedEvent;
import com.plantarena.plants.application.port.in.ArchivePlantUseCase;
import com.plantarena.plants.application.port.in.GetPlantModerationUseCase;
import com.plantarena.plants.application.port.in.GetPlantUseCase;
import com.plantarena.plants.application.port.in.ListPlantsUseCase;
import com.plantarena.plants.application.port.in.RenamePlantUseCase;
import com.plantarena.plants.application.port.in.SubmitPlantUseCase;
import com.plantarena.plants.application.port.out.MediaAssetClaimsGateway;
import com.plantarena.plants.application.port.out.MediaAssetsGateway;
import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.ImageRestrictionRepository;
import com.plantarena.plants.domain.ImageReusePolicy;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import com.plantarena.plants.domain.PlantReservationRepository;
import com.plantarena.shared.event.IntegrationEventPublisher;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases растений (разделы 6 и 13). Подача — межконтекстный процесс
 * «Plant (plants) + claim (media)» в одной транзакции монолита (раздел 12,
 * ADR-008); в лабе №2 — команда в file-service с retry/компенсацией.
 * Публикует PlantSubmitted (moderation подписан, итерация 4).
 */
@Service
public class PlantService implements SubmitPlantUseCase, ListPlantsUseCase, GetPlantUseCase,
        RenamePlantUseCase, ArchivePlantUseCase, GetPlantModerationUseCase {

    private final PlantRepository plants;
    private final ImageRestrictionRepository restrictions;
    private final PlantReservationRepository reservations;
    private final MediaAssetsGateway mediaAssets;
    private final MediaAssetClaimsGateway mediaClaims;
    private final PlantsAccessPolicy accessPolicy;
    private final IntegrationEventPublisher eventPublisher;
    private final ImageReusePolicy reusePolicy = new ImageReusePolicy();
    private final Clock clock;

    public PlantService(PlantRepository plants, ImageRestrictionRepository restrictions,
                        PlantReservationRepository reservations, MediaAssetsGateway mediaAssets,
                        MediaAssetClaimsGateway mediaClaims, PlantsAccessPolicy accessPolicy,
                        IntegrationEventPublisher eventPublisher, Clock clock) {
        this.plants = plants;
        this.restrictions = restrictions;
        this.reservations = reservations;
        this.mediaAssets = mediaAssets;
        this.mediaClaims = mediaClaims;
        this.accessPolicy = accessPolicy;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PlantData submit(CurrentActor actor, SubmitPlantCommand command) {
        accessPolicy.requireIdentified(actor);
        MediaAssetsGateway.AssetMetadata asset = mediaAssets.findById(command.assetId())
            .orElseThrow(() -> new AssetNotFoundException("Файл не найден: " + command.assetId()));
        if (!asset.ownerId().equals(actor.userId())) {
            throw new AssetNotFoundException("Файл не найден"); // чужой скрыт (раздел 13)
        }
        plants.findActiveByAssetId(asset.id()).ifPresent(existing -> {
            throw new AssetAlreadyClaimedException(
                "Файл уже задействован растением: " + existing.id());
        });
        ImageFingerprint fingerprint =
            new ImageFingerprint(asset.fingerprint(), asset.fingerprintVersion());
        requireNotRestricted(actor.userId(), fingerprint);

        Plant plant = Plant.submit(actor.userId(), asset.id(), fingerprint,
            command.title(), clock.instant());
        plants.save(plant);
        mediaClaims.claim(asset.id(), plant.id(), false); // занят, но не публичен (ADR-008)

        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new PlantSubmittedEvent(eventId, PlantSubmittedEvent.TYPE,
            PlantSubmittedEvent.SCHEMA_VERSION, plant.id(), plant.version(), clock.instant(),
            eventId, // correlationId: сквозная корреляция появится с Kafka (лаба №4)
            new PlantSubmittedEvent.Payload(plant.id(), plant.ownerId(), plant.assetId(),
                plant.fingerprint().value(), plant.fingerprint().algorithmVersion())));
        return toData(plant);
    }

    @Override
    @Transactional(readOnly = true)
    public PlantListResult list(CurrentActor actor, UUID ownerId, int page, int size) {
        accessPolicy.requireIdentified(actor);
        UUID targetOwner = ownerId == null ? actor.userId() : ownerId;
        boolean ownView = targetOwner.equals(actor.userId()) || actor.hasRole(AppRole.ADMIN);
        List<Plant> items = ownView
            ? plants.findByOwner(targetOwner, page * size, size)
            : plants.findApprovedByOwner(targetOwner, page * size, size);
        long total = ownView
            ? plants.countByOwner(targetOwner)
            : plants.countApprovedByOwner(targetOwner);
        return new PlantListResult(items.stream().map(PlantService::toData).toList(), total);
    }

    @Override
    @Transactional(readOnly = true)
    public PlantData get(CurrentActor actor, UUID plantId) {
        Plant plant = loadVisible(plantId);
        accessPolicy.requirePlantViewer(actor, plant);
        return toData(plant);
    }

    @Override
    @Transactional
    public PlantData rename(CurrentActor actor, UUID plantId, String title) {
        Plant plant = loadVisible(plantId);
        accessPolicy.requirePlantViewer(actor, plant);
        accessPolicy.requirePlantOwner(actor, plant);
        plant.rename(title);
        return toData(plants.save(plant));
    }

    @Override
    @Transactional
    public void archive(CurrentActor actor, UUID plantId) {
        Plant plant = loadVisible(plantId);
        accessPolicy.requirePlantViewer(actor, plant);
        accessPolicy.requirePlantOwner(actor, plant);
        if (reservations.findActiveByPlantId(plant.id()).isPresent()) {
            throw new PlantUnderReservationException(
                "Растение под активным резервом изображения: " + plant.id());
        }
        plant.archive(clock.instant());
        plants.save(plant);
        mediaClaims.release(plant.assetId(), plant.id()); // файл освобождён (ADR-008)
    }

    @Override
    @Transactional(readOnly = true)
    public ModerationStatusResult moderation(CurrentActor actor, UUID plantId) {
        Plant plant = loadVisible(plantId);
        accessPolicy.requireModerationViewer(actor, plant);
        return new ModerationStatusResult(
            PlantModerationStatus.valueOf(plant.moderationStatus().name()),
            plant.moderationReason(),
            plant.moderationStatus() == ModerationStatus.REJECTED);
    }

    private void requireNotRestricted(UUID ownerId, ImageFingerprint fingerprint) {
        List<ImageRestriction> history =
            restrictions.findByOwnerAndFingerprint(ownerId, fingerprint.value());
        reusePolicy.activeRestriction(history, clock.instant())
            .ifPresent(restriction -> {
                throw new ImageRestrictedException(restriction);
            });
    }

    /** Архивированное растение скрыто для всех, включая владельца (раздел 13). */
    private Plant loadVisible(UUID plantId) {
        Plant plant = plants.findById(plantId)
            .orElseThrow(() -> new PlantNotFoundException("Растение не найдено: " + plantId));
        if (plant.archivedAt() != null) {
            throw new PlantNotFoundException("Растение не найдено: " + plantId);
        }
        return plant;
    }

    private static PlantData toData(Plant plant) {
        return new PlantData(plant.id(), plant.ownerId(), plant.assetId(), plant.title(),
            PlantModerationStatus.valueOf(plant.moderationStatus().name()),
            PlantLifeStatus.valueOf(plant.lifeStatus().name()),
            plant.createdAt(), plant.diedAt(), plant.archivedAt());
    }
}
