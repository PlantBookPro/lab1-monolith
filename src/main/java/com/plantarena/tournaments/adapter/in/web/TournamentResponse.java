package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.api.TournamentData;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;


public record TournamentResponse(
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

    public static TournamentResponse from(TournamentData data) {
        return new TournamentResponse(data.id(), data.creatorId(), data.name(),
            data.description(), data.type(), data.status(), data.algorithm(),
            data.registrationDeadline(), data.roundDurationSeconds(),
            data.eliminationFraction(), data.minParticipants(), data.cancelReason(),
            data.tagIds(), data.createdAt());
    }
}
