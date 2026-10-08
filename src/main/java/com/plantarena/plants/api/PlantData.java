package com.plantarena.plants.api;

import java.time.Instant;
import java.util.UUID;


public record PlantData(
        UUID id,
        UUID ownerId,
        UUID assetId,
        String title,
        PlantModerationStatus moderationStatus,
        PlantLifeStatus lifeStatus,
        Instant createdAt,
        Instant diedAt,
        Instant archivedAt) {
}
