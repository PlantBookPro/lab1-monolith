package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.util.UUID;

/** Отменить турнир до RUNNING с освобождением резервов (раздел 7). */
public interface CancelTournamentUseCase {

    TournamentData cancel(CurrentActor actor, UUID tournamentId);
}
