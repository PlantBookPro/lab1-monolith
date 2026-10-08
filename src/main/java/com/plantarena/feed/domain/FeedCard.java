package com.plantarena.feed.domain;

import java.time.Instant;
import java.util.UUID;


public record FeedCard(
        UUID id,
        UUID windowId,
        UUID tournamentId,
        String scope,
        UUID clusterId,
        UUID entryId,
        UUID userId,
        UUID plantId,
        UUID assetId,
        String title,
        String ownerDisplayName,
        Instant joinedAt,
        Instant closesAt,
        Instant createdAt,
        long sortKey) {
}
