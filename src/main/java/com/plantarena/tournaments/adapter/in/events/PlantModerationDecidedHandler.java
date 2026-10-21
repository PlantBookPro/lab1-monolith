package com.plantarena.tournaments.adapter.in.events;

import com.plantarena.plants.api.event.PlantModerationDecidedEvent;
import com.plantarena.tournaments.application.port.in.OnPlantModerationDecidedUseCase;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;


@Component
@lombok.RequiredArgsConstructor
public class PlantModerationDecidedHandler {

    private final OnPlantModerationDecidedUseCase onPlantModerationDecided;

    @EventListener
    public void onPlantModerationDecided(PlantModerationDecidedEvent event) {
        onPlantModerationDecided.onPlantModerationDecided(
            event.payload().plantId(), event.payload().decision());
    }
}
