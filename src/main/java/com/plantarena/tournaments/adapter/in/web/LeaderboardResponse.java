package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.application.port.in.GetLeaderboardUseCase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Лидерборд окна (раздел 13): счёт выбранного окна, позиции, итоги. */
public record LeaderboardResponse(UUID windowId, int sequence, String status, Instant closesAt,
                                   List<Item> items) {

    static LeaderboardResponse from(GetLeaderboardUseCase.LeaderboardResult result) {
        return new LeaderboardResponse(result.windowId(), result.sequence(), result.status(),
            result.closesAt(),
            result.items().stream()
                .map(item -> new Item(item.position(), item.entryId(), item.userId(),
                    item.score(), item.result()))
                .toList());
    }

    record Item(int position, UUID entryId, UUID userId, long score, String result) {
    }
}
