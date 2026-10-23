package com.plantarena.plants.application.port.out;

import java.util.Optional;
import java.util.UUID;


public interface MediaAssetsGateway {

    Optional<AssetMetadata> findById(UUID assetId);

    
    record AssetMetadata(UUID id, UUID ownerId, String fingerprint, int fingerprintVersion) {
    }
}
