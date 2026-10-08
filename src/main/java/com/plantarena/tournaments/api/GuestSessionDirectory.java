package com.plantarena.tournaments.api;

import java.util.Optional;
import java.util.UUID;


public interface GuestSessionDirectory {

    
    Optional<UUID> activeSessionId(String rawToken);
}
