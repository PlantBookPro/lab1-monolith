package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.EntryData;
import java.util.List;
import java.util.UUID;

/** Участники/результаты турнира (раздел 13). */
public interface ListEntriesUseCase {

    EntryListResult list(CurrentActor actor, UUID tournamentId, int page, int size);

    record EntryListResult(List<EntryData> items, long total) {
    }
}
