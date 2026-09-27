package com.plantarena.tournaments.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованные данные приглашения (раздел 13): reservationId — внутренний
 * идентификатор plants, наружу не выходит.
 */
public record InvitationData(
        UUID id,
        UUID tournamentId,
        UUID userId,
        String status,
        Instant invitedAt,
        Instant respondedAt,
        UUID submittedPlantId) {
}
