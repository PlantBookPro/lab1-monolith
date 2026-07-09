package com.plantarena.plants.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованные данные растения (раздел 6): без fingerprint наружу —
 * отпечаток внутренний инструмент запретов, публично не нужен.
 */
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
