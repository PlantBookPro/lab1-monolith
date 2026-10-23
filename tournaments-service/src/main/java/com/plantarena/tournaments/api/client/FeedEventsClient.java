package com.plantarena.tournaments.api.client;

import com.plantarena.tournaments.api.client.fallback.FeedEventsClientFallback;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "feed-service", fallback = FeedEventsClientFallback.class)
public interface FeedEventsClient {

    @PostMapping("/internal/feed/projections")
    void windowOpened(@RequestBody WindowProjectionRequest request);

    @DeleteMapping("/internal/feed/projections/{windowId}")
    void windowClosed(@PathVariable("windowId") UUID windowId);

    record WindowProjectionRequest(UUID windowId, UUID tournamentId, String scope, UUID clusterId,
                                   Instant closesAt, List<CardSeedView> cards) {}

    record CardSeedView(UUID entryId, UUID userId, UUID plantId, Instant joinedAt) {}
}
