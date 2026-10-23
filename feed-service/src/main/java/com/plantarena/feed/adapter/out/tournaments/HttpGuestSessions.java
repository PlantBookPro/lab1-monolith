package com.plantarena.feed.adapter.out.tournaments;

import com.plantarena.feed.api.client.TournamentsDirectoryClient;
import com.plantarena.feed.application.port.out.GuestSessions;
import feign.FeignException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpGuestSessions implements GuestSessions {

    private final TournamentsDirectoryClient tournamentsClient;

    @Override
    public Optional<UUID> activeSessionId(String rawToken) {
        try {
            return Optional.of(tournamentsClient.activeSession(rawToken).sessionId());
        } catch (FeignException.NotFound e) {
            return Optional.empty();
        }
    }
}
