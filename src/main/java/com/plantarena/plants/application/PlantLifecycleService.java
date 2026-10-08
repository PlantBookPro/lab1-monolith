package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantLifecycle;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.event.PlantDiedEvent;
import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.ImageRestrictionRepository;
import com.plantarena.plants.domain.LifeStatus;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import com.plantarena.shared.event.IntegrationEventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class PlantLifecycleService implements PlantLifecycle {

    private final PlantRepository plants;
    private final ImageRestrictionRepository restrictions;
    private final IntegrationEventPublisher eventPublisher;
    private final Clock clock;

    public PlantLifecycleService(PlantRepository plants, ImageRestrictionRepository restrictions,
                                 IntegrationEventPublisher eventPublisher, Clock clock) {
        this.plants = plants;
        this.restrictions = restrictions;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void registerDeath(UUID plantId, RestrictionKind kind, Instant cooldownExpiresAt,
                              String reason, UUID sourceEntryId) {
        Objects.requireNonNull(kind, "kind");
        if (kind == RestrictionKind.COOLDOWN && cooldownExpiresAt == null) {
            throw new IllegalArgumentException("COOLDOWN требует cooldownExpiresAt");
        }
        if (kind == RestrictionKind.PERMANENT && cooldownExpiresAt != null) {
            throw new IllegalArgumentException("PERMANENT не принимает cooldownExpiresAt");
        }
        Plant plant = plants.findById(plantId)
            .orElseThrow(() -> new PlantNotFoundException("Растение не найдено: " + plantId));
        if (plant.lifeStatus() == LifeStatus.DEAD) {
            return; 
        }
        plant.die(clock.instant());
        plants.save(plant);

        ImageRestriction restriction = switch (kind) {
            case PERMANENT -> ImageRestriction.permanent(plant.ownerId(), plant.fingerprint(),
                reason, sourceEntryId, clock.instant());
            case COOLDOWN -> ImageRestriction.cooldown(plant.ownerId(), plant.fingerprint(),
                reason, sourceEntryId, cooldownExpiresAt, clock.instant());
        };
        restrictions.save(restriction); 

        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new PlantDiedEvent(eventId, PlantDiedEvent.TYPE,
            PlantDiedEvent.SCHEMA_VERSION, plant.id(), plant.version(), clock.instant(), eventId,
            new PlantDiedEvent.Payload(plant.id(), plant.ownerId(),
                plant.fingerprint().value(), kind.name(), sourceEntryId)));
    }
}
