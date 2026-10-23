package com.plantarena.plants.api;

import java.util.Optional;
import java.util.UUID;


public interface PlantDirectory {

    Optional<PlantData> findById(UUID plantId);
}
