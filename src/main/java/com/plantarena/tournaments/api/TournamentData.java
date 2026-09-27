package com.plantarena.tournaments.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Опубликованные данные турнира (раздел 13). reservationId и внутренние
 * детали заявок не раскрываются; маппинг из домена — в application
 * (TournamentAssembler): api не зависит от domain (LayerRules).
 */
public record TournamentData(
        UUID id,
        UUID creatorId,
        String name,
        String description,
        String type,
        String status,
        String algorithm,
        Instant registrationDeadline,
        long roundDurationSeconds,
        double eliminationFraction,
        int minParticipants,
        String cancelReason,
        Set<UUID> tagIds,
        Instant createdAt) {
}
