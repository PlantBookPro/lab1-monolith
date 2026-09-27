package com.plantarena.tournaments.adapter.out.plants;

import com.plantarena.plants.api.PlantData;
import com.plantarena.plants.api.PlantDirectory;
import com.plantarena.plants.api.PlantModerationStatus;
import com.plantarena.tournaments.application.port.out.PlantDirectoryGateway;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL-адаптер read-контракта plants.api.PlantDirectory (дизайн итерации 5,
 * решение 6): PlantData → минимальный снимок tournaments (владелец + факт
 * одобрения модерацией). Права не проверяются — внутренний контракт
 * монолита, видимость решает tournaments. В лабе №2 — HTTP-клиент.
 */
@Component
public class InProcessPlantDirectory implements PlantDirectoryGateway {

    private final PlantDirectory plantDirectory;

    public InProcessPlantDirectory(PlantDirectory plantDirectory) {
        this.plantDirectory = plantDirectory;
    }

    @Override
    public Optional<PlantSnapshot> findById(UUID plantId) {
        return plantDirectory.findById(plantId)
            .map(this::toSnapshot);
    }

    private PlantSnapshot toSnapshot(PlantData plant) {
        return new PlantSnapshot(plant.id(), plant.ownerId(),
            plant.moderationStatus() == PlantModerationStatus.APPROVED);
    }
}
