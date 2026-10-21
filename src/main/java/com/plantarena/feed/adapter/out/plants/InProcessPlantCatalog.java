package com.plantarena.feed.adapter.out.plants;

import com.plantarena.feed.application.port.out.PlantCatalog;
import com.plantarena.plants.api.PlantDirectory;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class InProcessPlantCatalog implements PlantCatalog {

    private final PlantDirectory plantDirectory;

    @Override
    public Optional<PlantView> findPlant(UUID plantId) {
        return plantDirectory.findById(plantId).map(plant -> new PlantView(plant.id(),
            plant.assetId(), plant.title(), plant.lifeStatus().name()));
    }
}
