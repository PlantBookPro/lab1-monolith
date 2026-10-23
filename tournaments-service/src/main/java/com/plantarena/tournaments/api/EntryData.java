package com.plantarena.tournaments.api;

import java.time.Instant;
import java.util.UUID;


public record EntryData(
        UUID id,
        UUID tournamentId,
        UUID userId,
        UUID plantId,
        String status,
        Instant joinedAt) {
}
