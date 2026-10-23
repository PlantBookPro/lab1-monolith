package com.plantarena.moderation.adapter.out.plants;

import com.plantarena.moderation.api.client.PlantsDirectoryClient;
import com.plantarena.moderation.application.DecisionConflictException;
import com.plantarena.moderation.application.port.out.PlantModerationGateway;
import feign.FeignException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class HttpPlantModerationGateway implements PlantModerationGateway {

    private final PlantsDirectoryClient plantsClient;

    @Override
    public void recordDecision(UUID plantId, Decision decision, String reason) {
        try {
            plantsClient.recordDecision(plantId,
                new PlantsDirectoryClient.DecisionRequest(decision.name(), reason));
        } catch (FeignException.Conflict e) {
            throw new DecisionConflictException(e.getMessage());
        }
    }
}
