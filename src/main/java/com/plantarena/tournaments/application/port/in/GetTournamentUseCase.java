package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.util.UUID;

/** Просмотр турнира: организатор/админ/приглашённый/участник (раздел 13). */
public interface GetTournamentUseCase {

    TournamentData get(CurrentActor actor, UUID tournamentId);
}
