package com.plantarena.plants.api;

import java.util.UUID;


public interface PlantModeration {

    
    PlantData recordDecision(UUID plantId, Decision decision, String reason);

    enum Decision {
        APPROVED, REJECTED
    }
}
