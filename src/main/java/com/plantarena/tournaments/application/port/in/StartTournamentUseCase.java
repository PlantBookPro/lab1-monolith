package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.time.Instant;
import java.util.UUID;


public interface StartTournamentUseCase {

    TournamentData start(CurrentActor actor, UUID tournamentId);

    
    int startDue(Instant now, int limit);
}
