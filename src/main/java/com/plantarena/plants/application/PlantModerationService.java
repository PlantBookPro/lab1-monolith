package com.plantarena.plants.application;

import com.plantarena.plants.api.ModerationAlreadyDecidedException;
import com.plantarena.plants.api.PlantData;
import com.plantarena.plants.api.PlantLifeStatus;
import com.plantarena.plants.api.PlantModeration;
import com.plantarena.plants.api.PlantModerationStatus;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.event.PlantModerationDecidedEvent;
import com.plantarena.plants.application.port.out.MediaAssetClaimsGateway;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantAlreadyDecidedException;
import com.plantarena.plants.domain.PlantRepository;
import com.plantarena.shared.event.IntegrationEventPublisher;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class PlantModerationService implements PlantModeration {

    private final PlantRepository plants;
    private final MediaAssetClaimsGateway mediaClaims;
    private final IntegrationEventPublisher eventPublisher;
    private final Clock clock;

    public PlantModerationService(PlantRepository plants, MediaAssetClaimsGateway mediaClaims,
                                  IntegrationEventPublisher eventPublisher, Clock clock) {
        this.plants = plants;
        this.mediaClaims = mediaClaims;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PlantData recordDecision(UUID plantId, Decision decision, String reason) {
        Plant plant = plants.findById(plantId)
            .orElseThrow(() -> new PlantNotFoundException("Растение не найдено: " + plantId));
        ModerationStatus target = ModerationStatus.valueOf(decision.name());
        boolean changed;
        try {
            changed = plant.applyDecision(target, reason);
        } catch (PlantAlreadyDecidedException e) {
            throw new ModerationAlreadyDecidedException(e.getMessage());
        }
        if (!changed) {
            return toData(plant); 
        }
        plants.save(plant);
        if (decision == Decision.APPROVED) {
            mediaClaims.claim(plant.assetId(), plant.id(), true); 
        }
        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new PlantModerationDecidedEvent(eventId,
            PlantModerationDecidedEvent.TYPE, PlantModerationDecidedEvent.SCHEMA_VERSION,
            plant.id(), plant.version(), clock.instant(), eventId,
            new PlantModerationDecidedEvent.Payload(plant.id(), plant.ownerId(),
                decision.name(), reason)));
        return toData(plant);
    }

    private static PlantData toData(Plant plant) {
        return new PlantData(plant.id(), plant.ownerId(), plant.assetId(), plant.title(),
            PlantModerationStatus.valueOf(plant.moderationStatus().name()),
            PlantLifeStatus.valueOf(plant.lifeStatus().name()),
            plant.createdAt(), plant.diedAt(), plant.archivedAt());
    }
}
