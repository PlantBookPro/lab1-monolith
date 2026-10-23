package com.plantarena.tournaments.domain;

import java.util.Optional;
import java.util.UUID;


public interface GuestSessionRepository {

    GuestSession save(GuestSession session);

    
    Optional<GuestSession> findByTokenHash(String tokenHash);
}
