package com.plantarena.tournaments.adapter.out.plants;

import com.plantarena.tournaments.api.client.PlantsDirectoryClient;
import com.plantarena.tournaments.application.port.out.PlantDirectoryGateway;
import feign.FeignException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpPlantDirectory implements PlantDirectoryGateway {

    private final PlantsDirectoryClient plantsClient;

    @Override
    public Optional<PlantSnapshot> findById(UUID plantId) {
        try {
            PlantsDirectoryClient.PlantView plant = plantsClient.getPlant(plantId);
            return Optional.of(new PlantSnapshot(plant.id(), plant.ownerId(),
                "APPROVED".equals(plant.moderationStatus())));
        } catch (FeignException.NotFound e) {
            return Optional.empty();
        }
    }
}
