package com.plantarena.plants.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованное событие: модерация решила судьбу заявки (раздел 4.3).
 * tournaments подписан и переводит заявку (READY / возврат в INVITED, итерация 5).
 */
public record PlantModerationDecidedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "PlantModerationDecided";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID plantId, UUID ownerId, String decision, String reason) {
    }
}
