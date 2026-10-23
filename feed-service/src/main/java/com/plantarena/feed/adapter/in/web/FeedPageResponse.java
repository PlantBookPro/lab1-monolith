package com.plantarena.feed.adapter.in.web;

import com.plantarena.feed.application.port.in.GetFeedUseCase;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;


public record FeedPageResponse(
        List<FeedItemResponse> items,
        @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor,
        boolean hasNext) {

    public static FeedPageResponse from(GetFeedUseCase.FeedPage page) {
        return new FeedPageResponse(
            page.items().stream()
                .map(item -> new FeedItemResponse(item.windowId(), item.scope(),
                    item.tournamentId(), item.entryId(), item.plantId(), item.title(),
                    item.imageUrl(), new OwnerResponse(item.ownerId(),
                        item.ownerDisplayName()), item.closesAt()))
                .toList(),
            page.nextCursor(), page.hasNext());
    }
}
