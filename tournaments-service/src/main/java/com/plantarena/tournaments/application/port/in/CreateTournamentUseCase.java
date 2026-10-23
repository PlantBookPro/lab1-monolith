package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;


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
