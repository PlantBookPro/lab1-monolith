package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.web.PaginationParams;
import java.time.Instant;
import java.util.List;
import java.util.UUID;


public interface GetGlobalLeaderboardUseCase {

    GlobalLeaderboard finalLeaderboard(PaginationParams page);

    GlobalLeaderboard clusterLeaderboard(UUID clusterId, PaginationParams page);

    record GlobalLeaderboard(String scope, UUID windowId, Instant closesAt, Instant asOf,
                             List<Item> items) {
    }

    record Item(UUID entryId, UUID userId, long score) {
    }
}
