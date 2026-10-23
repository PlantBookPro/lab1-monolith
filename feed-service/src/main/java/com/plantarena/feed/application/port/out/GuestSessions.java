package com.plantarena.feed.application.port.out;

import java.util.Optional;
import java.util.UUID;


public interface GuestSessions {

    
    Optional<UUID> activeSessionId(String rawToken);
}
