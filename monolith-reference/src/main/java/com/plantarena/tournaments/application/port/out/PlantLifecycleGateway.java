package com.plantarena.tournaments.application.port.out;

import java.time.Instant;
import java.util.UUID;


public interface PlantLifecycleGateway {

    
    void registerDeath(UUID plantId, RestrictionKind kind, Instant cooldownExpiresAt,
                       String reason, UUID sourceEntryId);

    enum RestrictionKind {
        PERMANENT, COOLDOWN
    }
}
