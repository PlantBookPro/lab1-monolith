package com.plantarena.plants.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;


public record PlantDiedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "PlantDied";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID plantId, UUID ownerId, String fingerprint,
                          String restrictionKind, UUID sourceEntryId) {
    }
}
