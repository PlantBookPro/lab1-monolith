package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;


public record UpdateTournamentRequest(
        @Size(min = 1, max = 100) String name,
        @Size(max = 2000) String description,
        @Future Instant registrationDeadline,
        @Positive Long roundDurationSeconds,
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "1", inclusive = false) Double eliminationFraction,
        @Min(2) Integer minParticipants,
        Set<UUID> tagIds) {
}
