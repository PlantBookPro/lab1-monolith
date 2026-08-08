package com.plantarena.tournaments.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованное событие: турнир завершён, победитель определён (раздел 16).
 * Подписчики — лента (итерация 8) и notification-service лабы №4.
 */
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
