package com.plantarena.plants.api;

import java.util.UUID;


public interface PlantEligibility {

    
    UUID reserveSubmission(UUID ownerId, UUID plantId, UUID idempotencyKey);

    
    void confirmEligibility(UUID ownerId, UUID plantId, UUID reservationId);

    
    void releaseReservation(UUID reservationId);
}
