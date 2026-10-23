package com.plantarena.feed.adapter.out.plants;

import com.plantarena.feed.api.client.PlantsDirectoryClient;
import com.plantarena.feed.application.port.out.PlantCatalog;
import feign.FeignException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpPlantCatalog implements PlantCatalog {

    private final PlantsDirectoryClient plantsClient;

    @Override
    public Optional<PlantView> findPlant(UUID plantId) {
        try {
            PlantsDirectoryClient.PlantView plant = plantsClient.getPlant(plantId);
            return Optional.of(new PlantView(plant.id(), plant.assetId(), plant.title(),
                plant.lifeStatus()));
        } catch (FeignException.NotFound e) {
            return Optional.empty();
        }
    }
}
