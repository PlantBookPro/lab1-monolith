package com.plantarena.tournaments.application.port.out;

import java.util.UUID;


public interface PlantEligibilityGateway {

    
    UUID reserve(UUID ownerId, UUID plantId, UUID idempotencyKey);

    
    void confirm(UUID ownerId, UUID plantId, UUID reservationId);

    
    void release(UUID reservationId);
}
