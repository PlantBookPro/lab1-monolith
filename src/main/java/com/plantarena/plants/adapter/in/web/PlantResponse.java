package com.plantarena.plants.adapter.in.web;

import com.plantarena.plants.api.PlantData;
import com.plantarena.plants.api.PlantLifeStatus;
import com.plantarena.plants.api.PlantModerationStatus;
import java.time.Instant;
import java.util.UUID;

/** Ответ растений: без fingerprint — отпечаток внутренний инструмент запретов. */
public record PlantResponse(UUID id, UUID ownerId, UUID assetId, String title,
                            PlantModerationStatus moderationStatus, PlantLifeStatus lifeStatus,
                            Instant createdAt, Instant diedAt, Instant archivedAt) {

    public static PlantResponse from(PlantData plant) {
        return new PlantResponse(plant.id(), plant.ownerId(), plant.assetId(), plant.title(),
            plant.moderationStatus(), plant.lifeStatus(), plant.createdAt(), plant.diedAt(),
            plant.archivedAt());
    }
}
