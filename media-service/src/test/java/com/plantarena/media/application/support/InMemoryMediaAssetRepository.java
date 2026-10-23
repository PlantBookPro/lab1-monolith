package com.plantarena.media.application.support;

import com.plantarena.media.application.port.out.MediaAssetRepository;
import com.plantarena.media.domain.MediaAsset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory репозиторий для application-тестов (раздел 14.2). */
public class InMemoryMediaAssetRepository implements MediaAssetRepository {

    public final Map<UUID, MediaAsset> assets = new ConcurrentHashMap<>();
    public RuntimeException failureOnSave;

    @Override
    public MediaAsset save(MediaAsset asset) {
        if (failureOnSave != null) {
            throw failureOnSave;
        }
        assets.put(asset.id(), asset);
        return asset;
    }

    @Override
    public Optional<MediaAsset> findById(UUID id) {
        return Optional.ofNullable(assets.get(id));
    }

    @Override
    public void delete(UUID id) {
        assets.remove(id);
    }
}
