package com.plantarena.feed.application.port.out;

import java.util.Optional;
import java.util.UUID;


public interface PlantCatalog {

    Optional<PlantView> findPlant(UUID plantId);

    
    record PlantView(UUID plantId, UUID assetId, String title, String lifeStatus) {
    }
}
