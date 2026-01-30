package com.plantarena.tournaments.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Опубликованное событие: открыто окно голосования с зафиксированным
 * составом (раздел 9). Подписчик — проекция ленты feed (итерация 8);
 * в лабе №4 доставляется через outbox → Kafka. Несёт состав участников
 * (entry, владелец, растение) — feed обогащает карточки сам через
 * read-контракты plants/identity.
 */
public record VotingWindowOpenedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "VotingWindowOpened";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID windowId, UUID tournamentId, String scope, int sequence,
                          UUID epochId, UUID clusterId, String clusterKey,
                          Instant opensAt, Instant closesAt,
                          List<Participant> participants) {
    }

    public record Participant(UUID entryId, UUID userId, UUID plantId, Instant joinedAt) {
    }
}
