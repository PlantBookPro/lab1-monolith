package com.plantarena.feed.adapter.out.tournaments;

import com.plantarena.feed.application.port.out.GuestSessions;
import com.plantarena.tournaments.api.GuestSessionDirectory;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class InProcessGuestSessions implements GuestSessions {

    private final GuestSessionDirectory guestSessionDirectory;

    @Override
    public Optional<UUID> activeSessionId(String rawToken) {
        return guestSessionDirectory.activeSessionId(rawToken);
    }
}
