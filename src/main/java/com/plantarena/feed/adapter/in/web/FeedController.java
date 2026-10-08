package com.plantarena.feed.adapter.in.web;

import com.plantarena.feed.application.port.in.GetFeedUseCase;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@Tag(name = "feed")
public class FeedController {

    private final GetFeedUseCase getFeed;
    private final CurrentActorProvider currentActorProvider;

    public FeedController(GetFeedUseCase getFeed, CurrentActorProvider currentActorProvider) {
        this.getFeed = getFeed;
        this.currentActorProvider = currentActorProvider;
    }

    @GetMapping("/api/v1/feed")
    @Operation(operationId = "feed-get",
        summary = "Лента карточек для голосования: {items,nextCursor,hasNext} без total; "
            + "невалидный курсор — 400, истёкший — 410")
    public ResponseEntity<FeedPageResponse> get(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        CurrentActor actor = currentActorProvider.currentActor();
        GetFeedUseCase.FeedPage page = getFeed.get(actor, guestToken, limit, cursor);
        return ResponseEntity.ok(new FeedPageResponse(
            page.items().stream()
                .map(item -> new FeedItemResponse(item.windowId(), item.scope(),
                    item.tournamentId(), item.entryId(), item.plantId(), item.title(),
                    item.imageUrl(), new OwnerResponse(item.ownerId(),
                        item.ownerDisplayName()), item.closesAt()))
                .toList(),
            page.nextCursor(), page.hasNext()));
    }
}
