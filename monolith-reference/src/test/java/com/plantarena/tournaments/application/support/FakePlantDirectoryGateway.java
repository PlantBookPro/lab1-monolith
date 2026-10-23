package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.application.port.out.PlantDirectoryGateway;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Фейк read-порта растений: snapshot по plantId. */
public class FakePlantDirectoryGateway implements PlantDirectoryGateway {

    public final Map<UUID, PlantSnapshot> plants = new HashMap<>();

    @Override
    public Optional<PlantSnapshot> findById(UUID plantId) {
        return Optional.ofNullable(plants.get(plantId));
    }
}
