package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** История раундов турнира (раздел 13). */
public interface ListRoundsUseCase {

    RoundListResult listRounds(CurrentActor actor, UUID tournamentId, int page, int size);

    record RoundData(UUID id, int sequence, String status, Instant opensAt, Instant closesAt) {
    }

    record RoundListResult(List<RoundData> items, long total) {
    }
}
