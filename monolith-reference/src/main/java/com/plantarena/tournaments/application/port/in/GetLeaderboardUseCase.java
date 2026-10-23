package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;


public interface GetLeaderboardUseCase {

    LeaderboardResult get(CurrentActor actor, UUID tournamentId, UUID windowId,
                          int page, int size);

    record LeaderboardEntry(int position, UUID entryId, UUID userId, long score,
                            String result) {
    }

    record LeaderboardResult(UUID windowId, int sequence, String status, Instant closesAt,
                             List<LeaderboardEntry> items, long total) {
    }
}
