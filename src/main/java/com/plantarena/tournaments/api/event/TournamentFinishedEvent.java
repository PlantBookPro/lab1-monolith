package com.plantarena.tournaments.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;


public record TournamentFinishedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "TournamentFinished";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID tournamentId, UUID winnerEntryId, UUID winnerUserId,
                          UUID winnerPlantId) {
    }
}
