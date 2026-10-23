package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.webflux.Blocking;
import com.plantarena.tournaments.api.FeedDirectory;
import com.plantarena.tournaments.api.GuestSessionDirectory;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalFeedDirectoryController {

    private final FeedDirectory feedDirectory;
    private final GuestSessionDirectory guestSessionDirectory;

    @GetMapping("/feed/voted-entry-ids")
    public Mono<Set<UUID>> votedEntryIds(@RequestParam("subjectKey") String subjectKey) {
        return Blocking.call(() -> feedDirectory.findVotedEntryIdsInOpenWindows(subjectKey));
    }

    @GetMapping("/feed/participated-tournament-ids")
    public Mono<Set<UUID>> participatedTournamentIds(@RequestParam("userId") UUID userId) {
        return Blocking.call(() -> feedDirectory.findParticipatedTournamentIds(userId));
    }

    @GetMapping("/guest-sessions/active")
    public Mono<ActiveSessionResponse> activeSession(@RequestParam("token") String rawToken) {
        return Blocking.call(() -> guestSessionDirectory.activeSessionId(rawToken))
            .map(id -> id.orElseThrow(() -> new GuestSessionNotFound(rawToken)))
            .map(ActiveSessionResponse::new);
    }

    public record ActiveSessionResponse(UUID sessionId) {}

    public static class GuestSessionNotFound extends RuntimeException {
        public GuestSessionNotFound(String token) {
            super("Гостевая сессия не найдена или истекла");
        }
    }
}
