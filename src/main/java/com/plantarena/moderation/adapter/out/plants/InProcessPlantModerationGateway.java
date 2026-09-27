package com.plantarena.moderation.adapter.out.plants;

import com.plantarena.moderation.application.DecisionConflictException;
import com.plantarena.moderation.application.port.out.PlantModerationGateway;
import com.plantarena.plants.api.ModerationAlreadyDecidedException;
import com.plantarena.plants.api.PlantModeration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL-адаптер plants → moderation (раздел 4.3): решение передаётся командой
 * plants.api.PlantModeration.recordDecision. Конфликт решения по уже
 * решённой заявке переводится в термины moderation (DecisionConflictException
 * → задание завершается DONE/STALE). В лабе №2 меняется на HTTP-клиент.
 */
@Component
public class InProcessPlantModerationGateway implements PlantModerationGateway {

    private final PlantModeration plantModeration;

    public InProcessPlantModerationGateway(PlantModeration plantModeration) {
        this.plantModeration = plantModeration;
    }

    @Override
    public void recordDecision(UUID plantId, Decision decision, String reason) {
        try {
            plantModeration.recordDecision(plantId,
                PlantModeration.Decision.valueOf(decision.name()), reason);
        } catch (ModerationAlreadyDecidedException e) {
            throw new DecisionConflictException(e.getMessage());
        }
    }
}
