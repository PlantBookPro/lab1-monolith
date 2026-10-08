package com.plantarena.plants.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;


public interface PlantRepository {

    Plant save(Plant plant);

    Optional<Plant> findById(UUID id);

    
    Optional<Plant> findActiveByAssetId(UUID assetId);

    
    List<Plant> findByOwner(UUID ownerId, int offset, int limit);

    long countByOwner(UUID ownerId);

    
    List<Plant> findApprovedByOwner(UUID ownerId, int offset, int limit);

    long countApprovedByOwner(UUID ownerId);
}
