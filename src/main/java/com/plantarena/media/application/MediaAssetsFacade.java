package com.plantarena.media.application;

import com.plantarena.media.api.MediaAssetData;
import com.plantarena.media.api.MediaAssets;
import com.plantarena.media.api.MediaContent;
import com.plantarena.media.application.port.out.MediaAssetRepository;
import com.plantarena.media.domain.FileStorage;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реализация опубликованного контракта MediaAssets: метаданные и байты без
 * внутренних деталей (storageKey остаётся в media). loadContent — для
 * внутреннего контракта монолита (moderation, ADR-009): права не проверяются,
 * вызов идёт по assetId из задания модерации.
 */
@Service
@Transactional(readOnly = true)
public class MediaAssetsFacade implements MediaAssets {

    private final MediaAssetRepository repository;
    private final FileStorage fileStorage;

    public MediaAssetsFacade(MediaAssetRepository repository, FileStorage fileStorage) {
        this.repository = repository;
        this.fileStorage = fileStorage;
    }

    @Override
    public Optional<MediaAssetData> findById(UUID assetId) {
        return repository.findById(assetId)
            .map(asset -> new MediaAssetData(asset.id(), asset.ownerId(),
                asset.fingerprint().value(), asset.fingerprint().version()));
    }

    @Override
    public Optional<MediaContent> loadContent(UUID assetId) {
        return repository.findById(assetId)
            .map(asset -> new MediaContent(fileStorage.read(asset.storageKey()),
                asset.format().mimeType()));
    }
}
