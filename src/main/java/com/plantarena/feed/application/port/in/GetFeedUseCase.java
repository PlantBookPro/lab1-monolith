package com.plantarena.feed.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;


public interface GetFeedUseCase {

    
    FeedPage get(CurrentActor actor, String guestToken, Integer limit, String cursor);

    record FeedPage(List<FeedItem> items, String nextCursor, boolean hasNext) {
    }

    record FeedItem(UUID windowId, String scope, UUID tournamentId, UUID entryId,
                    UUID plantId, String title, String imageUrl, UUID ownerId,
                    String ownerDisplayName, Instant closesAt) {
    }
}
