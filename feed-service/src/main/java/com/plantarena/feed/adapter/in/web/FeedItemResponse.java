package com.plantarena.feed.adapter.in.web;

import java.time.Instant;
import java.util.UUID;


public record FeedItemResponse(
        UUID windowId,
        String scope,
        UUID tournamentId,
        UUID entryId,
        UUID plantId,
        String title,
        String imageUrl,
        OwnerResponse owner,
        Instant closesAt) {
}
