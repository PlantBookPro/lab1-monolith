package com.plantarena.feed.adapter.in.web;

import com.plantarena.feed.application.port.in.GetFeedUseCase;
import com.plantarena.shared.webflux.Blocking;
import com.plantarena.shared.webflux.ReactiveCurrentActorProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;


@RestController
@RequiredArgsConstructor
@Tag(name = "feed")
public class FeedController {

    private final GetFeedUseCase getFeed;
    private final ReactiveCurrentActorProvider actors;

    @GetMapping("/api/v1/feed")
    @Operation(operationId = "feed-get",
        summary = "Лента карточек для голосования: {items,nextCursor,hasNext} без total; "
            + "невалидный курсор — 400, истёкший — 410")
    public Mono<FeedPageResponse> feed(
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        return actors.currentActor()
            .flatMap(actor -> Blocking.call(() -> getFeed.get(actor, guestToken, limit, cursor))
                .map(FeedPageResponse::from));
    }
}
