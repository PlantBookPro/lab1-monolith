package com.plantarena.feed.api.client;

import com.plantarena.feed.api.client.fallback.TournamentsDirectoryClientFallback;
import java.util.Set;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "tournaments-service", fallback = TournamentsDirectoryClientFallback.class)
public interface TournamentsDirectoryClient {

    @GetMapping("/internal/feed/voted-entry-ids")
    Set<UUID> votedEntryIds(@RequestParam("subjectKey") String subjectKey);

    @GetMapping("/internal/feed/participated-tournament-ids")
    Set<UUID> participatedTournamentIds(@RequestParam("userId") UUID userId);

    @GetMapping("/internal/guest-sessions/active")
    ActiveSessionResponse activeSession(@RequestParam("token") String rawToken);

    record ActiveSessionResponse(UUID sessionId) {}
}
