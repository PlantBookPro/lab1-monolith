package com.plantarena.media.api;

import java.util.Optional;
import java.util.UUID;


public interface MediaAssets {

    Optional<MediaAssetData> findById(UUID assetId);

    
    Optional<MediaContent> loadContent(UUID assetId);
}
