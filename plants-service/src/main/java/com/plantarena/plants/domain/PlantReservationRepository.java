package com.plantarena.plants.domain;

import java.util.Optional;
import java.util.UUID;


public interface PlantReservationRepository {

    PlantReservation save(PlantReservation reservation);

    Optional<PlantReservation> findById(UUID id);

    
    Optional<PlantReservation> findByIdempotencyKey(UUID idempotencyKey);

    
    Optional<PlantReservation> findActiveByOwnerAndFingerprint(UUID ownerId, String fingerprintValue);

    
    Optional<PlantReservation> findActiveByPlantId(UUID plantId);
}
