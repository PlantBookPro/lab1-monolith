package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/** Удалить пустой DRAFT (без приглашений), иначе 409 (раздел 13). */
public interface DeleteTournamentUseCase {

    void delete(CurrentActor actor, UUID tournamentId);
}
