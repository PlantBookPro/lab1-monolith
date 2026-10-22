package com.plantarena.tournaments.adapter.in.events;

import com.plantarena.plants.api.event.PlantModerationDecidedEvent;
import com.plantarena.tournaments.application.port.in.OnPlantModerationDecidedUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class PlantModerationDecidedHandler {

    private final OnPlantModerationDecidedUseCase onPlantModerationDecided;

    @EventListener
    public void onPlantModerationDecided(PlantModerationDecidedEvent event) {
        onPlantModerationDecided.onPlantModerationDecided(
            event.payload().plantId(), event.payload().decision());
    }
}
