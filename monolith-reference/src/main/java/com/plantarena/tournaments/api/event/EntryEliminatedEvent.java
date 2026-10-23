package com.plantarena.tournaments.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;


public record EntryEliminatedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "EntryEliminated";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID tournamentId, UUID entryId, UUID userId, UUID plantId,
                          int windowSequence) {
    }
}
