package com.plantarena.feed.adapter.out.plants;

import com.plantarena.feed.application.port.out.PlantCatalog;
import com.plantarena.plants.api.PlantDirectory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** ACL: публичные данные растения plants → порт feed (in-process). */
@Component
public class InProcessPlantCatalog implements PlantCatalog {

    private final PlantDirectory plantDirectory;

    public InProcessPlantCatalog(PlantDirectory plantDirectory) {
        this.plantDirectory = plantDirectory;
    }

    @Override
    public Optional<PlantView> findPlant(UUID plantId) {
        return plantDirectory.findById(plantId).map(plant -> new PlantView(plant.id(),
            plant.assetId(), plant.title(), plant.lifeStatus().name()));
    }
}
