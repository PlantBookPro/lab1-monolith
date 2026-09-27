package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Изменить турнир (организатор/админ): параметры — только в DRAFT, описание —
 * безопасное изменение и после открытия (раздел 13). null = не менять.
 */
public interface UpdateTournamentUseCase {

    TournamentData update(CurrentActor actor, UUID tournamentId, UpdateTournamentCommand command);

    record UpdateTournamentCommand(
            String name,
            String description,
            Instant registrationDeadline,
            Duration roundDuration,
            Double eliminationFraction,
            Integer minParticipants,
            Set<UUID> tagIds) {
    }
}
