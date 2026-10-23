package com.plantarena.tournaments.api;

import java.time.Instant;
import java.util.UUID;


public record InvitationData(
        UUID id,
        UUID tournamentId,
        UUID userId,
        String status,
        Instant invitedAt,
        Instant respondedAt,
        UUID submittedPlantId) {
}
