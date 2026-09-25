package com.plantarena.plants.application.port.in;

import com.plantarena.plants.api.PlantData;
import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/** Подача заявки «это моё растение» (раздел 13: POST /plants). */
public interface SubmitPlantUseCase {

    PlantData submit(CurrentActor actor, SubmitPlantCommand command);

    record SubmitPlantCommand(UUID assetId, String title) {
    }
}
