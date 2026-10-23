package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.util.UUID;


public interface CancelTournamentUseCase {

    TournamentData cancel(CurrentActor actor, UUID tournamentId);
}
