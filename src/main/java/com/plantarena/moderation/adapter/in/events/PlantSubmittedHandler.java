package com.plantarena.moderation.adapter.in.events;

import com.plantarena.moderation.application.port.in.CreateModerationJobUseCase;
import com.plantarena.plants.api.event.PlantSubmittedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;


@Component
public class PlantSubmittedHandler {

    private final CreateModerationJobUseCase createModerationJob;

    public PlantSubmittedHandler(CreateModerationJobUseCase createModerationJob) {
        this.createModerationJob = createModerationJob;
    }

    @EventListener
    public void onPlantSubmitted(PlantSubmittedEvent event) {
        createModerationJob.onPlantSubmitted(event.payload().plantId(),
            event.payload().assetId());
    }
}
