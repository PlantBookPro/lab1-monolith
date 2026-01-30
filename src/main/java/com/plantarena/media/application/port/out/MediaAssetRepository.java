package com.plantarena.media.application.port.out;

import com.plantarena.media.domain.MediaAsset;
import java.util.Optional;
import java.util.UUID;

/** Выходной порт репозитория агрегата MediaAsset (раздел 5). */
public interface MediaAssetRepository {

    MediaAsset save(MediaAsset asset);

    Optional<MediaAsset> findById(UUID id);

    void delete(UUID id);
}
