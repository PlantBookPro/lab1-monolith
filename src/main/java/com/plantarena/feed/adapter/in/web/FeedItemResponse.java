package com.plantarena.feed.adapter.in.web;

import java.time.Instant;
import java.util.UUID;

/** Карточка ленты (раздел 9): окно, участие, растение, публичный владелец. */
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
