package com.plantarena.moderation.adapter.in.jobs;

import com.plantarena.moderation.api.client.PlantsDirectoryClient;
import com.plantarena.moderation.application.port.in.CreateModerationJobUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
@Slf4j
public class ModerationPendingPlantPoller {

    private final PlantsDirectoryClient plantsClient;
    private final CreateModerationJobUseCase createModerationJob;

    @Scheduled(fixedDelay = 5000)
    public void poll() {
        try {
            for (var plant : plantsClient.getPlantsByModerationStatus("PENDING", 50)) {
                createModerationJob.onPlantSubmitted(plant.plantId(), plant.assetId());
            }
        } catch (Exception e) {
            log.warn("Опрос PENDING-растений не удался: {}", e.getMessage());
        }
    }
}
