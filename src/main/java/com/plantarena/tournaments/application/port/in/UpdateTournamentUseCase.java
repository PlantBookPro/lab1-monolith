package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;


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
