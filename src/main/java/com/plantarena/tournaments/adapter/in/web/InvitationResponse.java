package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.api.InvitationData;
import java.time.Instant;
import java.util.UUID;

/** Ответ REST по приглашению (раздел 13): reservationId не раскрывается. */
public record InvitationResponse(
        UUID id,
        UUID tournamentId,
        UUID userId,
        String status,
        Instant invitedAt,
        Instant respondedAt,
        UUID submittedPlantId) {

    public static InvitationResponse from(InvitationData data) {
        return new InvitationResponse(data.id(), data.tournamentId(), data.userId(),
            data.status(), data.invitedAt(), data.respondedAt(), data.submittedPlantId());
    }
}
