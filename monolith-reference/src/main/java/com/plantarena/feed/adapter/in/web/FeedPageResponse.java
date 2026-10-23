package com.plantarena.feed.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;


public record FeedPageResponse(
        List<FeedItemResponse> items,
        @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor,
        boolean hasNext) {
}
