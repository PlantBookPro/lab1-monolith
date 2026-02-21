package com.plantarena.media.application;

import com.plantarena.media.api.MediaAssetData;
import com.plantarena.media.api.MediaAssets;
import com.plantarena.media.application.port.out.MediaAssetRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация опубликованного контракта MediaAssets: чтение метаданных
 * без внутренних деталей (storageKey остаётся в media).
 */
@Service
@Transactional(readOnly = true)
public class MediaAssetsFacade implements MediaAssets {

    private final MediaAssetRepository repository;

    public MediaAssetsFacade(MediaAssetRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<MediaAssetData> findById(UUID assetId) {
        return repository.findById(assetId)
            .map(asset -> new MediaAssetData(asset.id(), asset.ownerId(),
                asset.fingerprint().value(), asset.fingerprint().version()));
    }
}
