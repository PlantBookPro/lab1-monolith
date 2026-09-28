package com.plantarena.tournaments.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Лидерборд глобального окна (раздел 8, алгоритм 9). */
public record GlobalLeaderboardResponse(String scope, UUID windowId, Instant closesAt,
                                        Instant asOf, List<Item> items) {

    record Item(UUID entryId, UUID userId, long score) {
    }
}
