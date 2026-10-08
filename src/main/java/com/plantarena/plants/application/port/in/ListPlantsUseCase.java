package com.plantarena.plants.application.port.in;

import com.plantarena.plants.api.PlantData;
import com.plantarena.shared.security.CurrentActor;
import java.util.List;
import java.util.UUID;


public interface ListPlantsUseCase {

    
    PlantListResult list(CurrentActor actor, UUID ownerId, int page, int size);

    record PlantListResult(List<PlantData> items, long total) {
    }
}
