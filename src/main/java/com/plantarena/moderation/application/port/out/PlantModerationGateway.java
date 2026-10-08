package com.plantarena.moderation.application.port.out;

import java.util.UUID;


public interface PlantModerationGateway {

    void recordDecision(UUID plantId, Decision decision, String reason);

    enum Decision {
        APPROVED, REJECTED
    }
}
