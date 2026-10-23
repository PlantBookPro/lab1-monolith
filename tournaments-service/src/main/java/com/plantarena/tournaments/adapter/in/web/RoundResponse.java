package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.application.port.in.ListRoundsUseCase;
import java.time.Instant;
import java.util.UUID;


public record RoundResponse(UUID id, int sequence, String status, Instant opensAt,
                            Instant closesAt) {

    static RoundResponse from(ListRoundsUseCase.RoundData data) {
        return new RoundResponse(data.id(), data.sequence(), data.status(), data.opensAt(),
            data.closesAt());
    }
}
