package com.plantarena.media.application.port.out;

import com.plantarena.media.domain.MediaAsset;
import java.util.Optional;
import java.util.UUID;


public interface MediaAssetRepository {

    MediaAsset save(MediaAsset asset);

    Optional<MediaAsset> findById(UUID id);

    void delete(UUID id);
}
