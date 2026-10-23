package com.plantarena.media.application;

import com.plantarena.media.domain.MediaAsset;
import java.time.Instant;
import java.util.UUID;


public record MediaAssetResult(UUID id, UUID ownerId, String mimeType, long byteSize,
                               int width, int height, Instant createdAt) {

    public static MediaAssetResult from(MediaAsset asset) {
        return new MediaAssetResult(asset.id(), asset.ownerId(), asset.format().mimeType(),
            asset.byteSize(), asset.width(), asset.height(), asset.createdAt());
    }
}
