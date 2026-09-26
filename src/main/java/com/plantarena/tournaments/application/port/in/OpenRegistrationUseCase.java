package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.util.UUID;

/** Открыть приём заявок: DRAFT → REGISTRATION_OPEN (раздел 7). */
public interface OpenRegistrationUseCase {

    TournamentData openRegistration(CurrentActor actor, UUID tournamentId);
}
