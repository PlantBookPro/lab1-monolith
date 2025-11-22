package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.api.EntryData;
import java.time.Instant;
import java.util.UUID;

/** Ответ REST по участию (раздел 13). */
public record EntryResponse(
        UUID id,
        UUID tournamentId,
        UUID userId,
        UUID plantId,
        String status,
        Instant joinedAt) {

    public static EntryResponse from(EntryData data) {
        return new EntryResponse(data.id(), data.tournamentId(), data.userId(),
            data.plantId(), data.status(), data.joinedAt());
    }
}
