package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.application.port.in.ListResultsUseCase;
import java.util.List;
import java.util.UUID;


public record ResultResponse(UUID winnerEntryId, List<Item> items) {

    static ResultResponse from(ListResultsUseCase.ResultListResult result) {
        return new ResultResponse(result.winnerEntryId(),
            result.items().stream()
                .map(item -> new Item(item.entryId(), item.userId(), item.plantId(),
                    item.status(), item.eliminatedInRound()))
                .toList());
    }

    record Item(UUID entryId, UUID userId, UUID plantId, String status,
                Integer eliminatedInRound) {
    }
}
