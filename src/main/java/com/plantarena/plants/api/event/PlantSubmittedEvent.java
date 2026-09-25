package com.plantarena.plants.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованное событие: создана заявка «это моё растение» (раздел 4.3).
 * moderation подписан на него и создаёт задание распознавания (итерация 4).
 */
public record PlantSubmittedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "PlantSubmitted";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID plantId, UUID ownerId, UUID assetId,
                          String fingerprint, int fingerprintVersion) {
    }
}
