package com.plantarena.tournaments.api.event;

import com.plantarena.shared.event.IntegrationEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованное событие: окно голосования закрыто (раздел 9). Подписчик —
 * проекция ленты feed (итерация 8): карточки окна удаляются; выжившие
 * возвращаются событием VotingWindowOpened следующего окна.
 */
public record VotingWindowClosedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        Payload payload) implements IntegrationEvent {

    public static final String TYPE = "VotingWindowClosed";
    public static final int SCHEMA_VERSION = 1;

    public record Payload(UUID windowId, UUID tournamentId, String scope, int sequence) {
    }
}
