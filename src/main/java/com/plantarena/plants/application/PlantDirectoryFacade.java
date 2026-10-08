package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantData;
import com.plantarena.plants.api.PlantDirectory;
import com.plantarena.plants.api.PlantLifeStatus;
import com.plantarena.plants.api.PlantModerationStatus;
import com.plantarena.plants.domain.PlantRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@Transactional(readOnly = true)
public class PlantDirectoryFacade implements PlantDirectory {

    private final PlantRepository plants;

    public PlantDirectoryFacade(PlantRepository plants) {
        this.plants = plants;
    }

    @Override
    public Optional<PlantData> findById(UUID plantId) {
        return plants.findById(plantId)
            .map(plant -> new PlantData(plant.id(), plant.ownerId(), plant.assetId(),
                plant.title(),
                PlantModerationStatus.valueOf(plant.moderationStatus().name()),
                PlantLifeStatus.valueOf(plant.lifeStatus().name()),
                plant.createdAt(), plant.diedAt(), plant.archivedAt()));
    }
}
