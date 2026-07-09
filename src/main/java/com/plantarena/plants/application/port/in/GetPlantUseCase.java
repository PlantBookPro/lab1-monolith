package com.plantarena.plants.application.port.in;

import com.plantarena.plants.api.PlantData;
import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/** Просмотр растения с учётом видимости (раздел 13: GET /plants/{id}). */
public interface GetPlantUseCase {

    PlantData get(CurrentActor actor, UUID plantId);
}
