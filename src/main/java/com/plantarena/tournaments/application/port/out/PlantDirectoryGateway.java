package com.plantarena.tournaments.application.port.out;

import java.util.Optional;
import java.util.UUID;


public interface PlantDirectoryGateway {

    Optional<PlantSnapshot> findById(UUID plantId);

    
    record PlantSnapshot(UUID plantId, UUID ownerId, boolean approved) {
    }
}
