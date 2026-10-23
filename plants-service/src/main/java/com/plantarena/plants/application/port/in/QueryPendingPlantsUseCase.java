package com.plantarena.plants.application.port.in;

import java.util.List;
import java.util.UUID;


public interface QueryPendingPlantsUseCase {

    List<PendingPlant> findPending(int limit);


    record PendingPlant(UUID plantId, UUID assetId) {
    }
}
