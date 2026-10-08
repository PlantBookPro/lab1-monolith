package com.plantarena.feed.adapter.out.tournaments;

import com.plantarena.feed.application.port.out.GuestSessions;
import com.plantarena.tournaments.api.GuestSessionDirectory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;


@Component
public class InProcessGuestSessions implements GuestSessions {

    private final GuestSessionDirectory guestSessionDirectory;

    public InProcessGuestSessions(GuestSessionDirectory guestSessionDirectory) {
        this.guestSessionDirectory = guestSessionDirectory;
    }

    @Override
    public Optional<UUID> activeSessionId(String rawToken) {
        return guestSessionDirectory.activeSessionId(rawToken);
    }
}
