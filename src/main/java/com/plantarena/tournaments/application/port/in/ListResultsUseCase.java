package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.List;
import java.util.UUID;

/** Итоги турнира: победитель и выбывшие (раздел 13). */
public interface ListResultsUseCase {

    ResultListResult listResults(CurrentActor actor, UUID tournamentId, int page, int size);

    record ResultData(UUID entryId, UUID userId, UUID plantId, String status,
                      Integer eliminatedInRound) {
    }

    record ResultListResult(UUID winnerEntryId, List<ResultData> items, long total) {
    }
}
