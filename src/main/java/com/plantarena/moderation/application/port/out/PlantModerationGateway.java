package com.plantarena.moderation.application.port.out;

import java.util.UUID;

/**
 * ACL-порт moderation к команде plants.api.PlantModeration.recordDecision.
 * Конфликт решения по уже решённой заявке — DecisionConflictException
 * (задание завершается DONE/STALE, раздел 6).
 */
public interface PlantModerationGateway {

    void recordDecision(UUID plantId, Decision decision, String reason);

    enum Decision {
        APPROVED, REJECTED
    }
}
