package com.plantarena.plants.application.port.in;

import com.plantarena.plants.api.PlantData;
import com.plantarena.shared.security.CurrentActor;
import java.util.List;
import java.util.UUID;

/** Список растений с учётом видимости (раздел 13: GET /plants, X-Total-Count). */
public interface ListPlantsUseCase {

    /**
     * @param ownerId владелец фильтра; null — свои растения
     */
    PlantListResult list(CurrentActor actor, UUID ownerId, int page, int size);

    record PlantListResult(List<PlantData> items, long total) {
    }
}
