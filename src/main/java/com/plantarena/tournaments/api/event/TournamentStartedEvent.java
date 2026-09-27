package com.plantarena.tournaments.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованное событие: турнир стартовал (раздел 16). Подписчики —
 * итерации 6–8 (окна/лента) и notification-service лабы №4.
 */
public record TournamentStartedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "TournamentStarted";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID tournamentId, UUID creatorId) {
    }
}
