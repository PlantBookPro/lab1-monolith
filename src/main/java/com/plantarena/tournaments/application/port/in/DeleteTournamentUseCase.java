package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;


public interface DeleteTournamentUseCase {

    void delete(CurrentActor actor, UUID tournamentId);
}
