package com.plantarena.tournaments.adapter.in.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;


public record CreateTournamentRequest(
        @NotBlank @Size(min = 1, max = 100) String name,
        @Size(max = 2000) String description,
        @NotNull @Future Instant registrationDeadline,
        @NotNull @Positive Long roundDurationSeconds,
        @NotNull @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "1", inclusive = false) Double eliminationFraction,
        @NotNull @Min(2) Integer minParticipants,
        Set<UUID> tagIds) {
}
