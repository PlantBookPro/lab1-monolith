package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.util.List;
import java.util.UUID;


public interface ListTournamentsUseCase {

    
    TournamentListResult list(CurrentActor actor, String status, UUID tagId,
                              int page, int size);

    record TournamentListResult(List<TournamentData> items, long total) {
    }
}
