package com.plantarena.plants.api;

import java.time.Instant;
import java.util.UUID;


public interface PlantLifecycle {

    
    void registerDeath(UUID plantId, RestrictionKind kind, Instant cooldownExpiresAt,
                       String reason, UUID sourceEntryId);

    enum RestrictionKind {
        PERMANENT, COOLDOWN
    }
}
