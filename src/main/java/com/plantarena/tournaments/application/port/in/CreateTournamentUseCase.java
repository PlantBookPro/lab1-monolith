package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Создать PRIVATE DRAFT (раздел 7/13; модератор/админ). */
public interface CreateTournamentUseCase {

    TournamentData create(CurrentActor actor, CreateTournamentCommand command);

    record CreateTournamentCommand(
            String name,
            String description,
            Instant registrationDeadline,
            Duration roundDuration,
            double eliminationFraction,
            int minParticipants,
            Set<UUID> tagIds) {
    }
}
