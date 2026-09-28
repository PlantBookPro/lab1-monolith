package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.web.PaginationParams;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Лидерборды глобального турнира (раздел 8, алгоритм 9): всегда scope,
 * windowId, closesAt, asOf; очки разных окон/кластеров не смешиваются.
 */
public interface GetGlobalLeaderboardUseCase {

    GlobalLeaderboard finalLeaderboard(PaginationParams page);

    GlobalLeaderboard clusterLeaderboard(UUID clusterId, PaginationParams page);

    record GlobalLeaderboard(String scope, UUID windowId, Instant closesAt, Instant asOf,
                             List<Item> items) {
    }

    record Item(UUID entryId, UUID userId, long score) {
    }
}
