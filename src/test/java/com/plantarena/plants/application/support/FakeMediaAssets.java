package com.plantarena.plants.application.support;

import com.plantarena.plants.application.port.out.MediaAssetsGateway;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Фейк порта MediaAssetsGateway: метаданные задаёт тест. */
public class FakeMediaAssets implements MediaAssetsGateway {

    public final Map<UUID, AssetMetadata> assets = new HashMap<>();

    public FakeMediaAssets addAsset(UUID assetId, UUID ownerId, String fingerprint) {
        assets.put(assetId, new AssetMetadata(assetId, ownerId, fingerprint, 1));
        return this;
    }

    @Override
    public Optional<AssetMetadata> findById(UUID assetId) {
        return Optional.ofNullable(assets.get(assetId));
    }
}
