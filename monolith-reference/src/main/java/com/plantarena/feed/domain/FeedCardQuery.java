package com.plantarena.feed.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;


public record FeedCardQuery(
        long seed,
        Instant snapshotCutoff,
        Instant now,
        Set<String> scopes,
        Set<UUID> participatedTournamentIds,
        UUID excludedOwnerUserId,
        Set<UUID> excludedEntryIds,
        Long lastSortKey,
        UUID lastId,
        int limit) {

    public FeedCardQuery {
        scopes = Set.copyOf(scopes);
        participatedTournamentIds = Set.copyOf(participatedTournamentIds);
        excludedEntryIds = Set.copyOf(excludedEntryIds);
        if (limit < 1) {
            throw new IllegalArgumentException("limit >= 1");
        }
    }
}
