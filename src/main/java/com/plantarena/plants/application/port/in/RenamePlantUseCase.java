package com.plantarena.plants.application.port.in;

import com.plantarena.plants.api.PlantData;
import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/** Переименование владельцем (раздел 13: PATCH /plants/{id}, только title). */
public interface RenamePlantUseCase {

    PlantData rename(CurrentActor actor, UUID plantId, String title);
}
