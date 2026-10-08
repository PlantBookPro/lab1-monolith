package com.plantarena.media.adapter.in.web;

import com.plantarena.media.application.MediaAssetResult;
import java.time.Instant;
import java.util.UUID;


public record MediaAssetResponse(UUID id, UUID ownerId, String mimeType, long byteSize,
                                 int width, int height, Instant createdAt) {

    public static MediaAssetResponse from(MediaAssetResult result) {
        return new MediaAssetResponse(result.id(), result.ownerId(), result.mimeType(),
            result.byteSize(), result.width(), result.height(), result.createdAt());
    }
}
