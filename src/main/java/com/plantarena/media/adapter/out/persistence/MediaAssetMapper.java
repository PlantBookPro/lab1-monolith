package com.plantarena.media.adapter.out.persistence;

import com.plantarena.media.domain.ImageFingerprint;
import com.plantarena.media.domain.ImageFormat;
import com.plantarena.media.domain.MediaAsset;

/** Явный маппинг домен ↔ JPA (раздел 5). */
public final class MediaAssetMapper {

    private MediaAssetMapper() {
    }

    public static MediaAssetJpaEntity toEntity(MediaAsset asset) {
        return new MediaAssetJpaEntity(asset.id(), asset.ownerId(), asset.storageKey(),
            asset.format().mimeType(), asset.byteSize(), asset.width(), asset.height(),
            asset.rawSha256(), asset.fingerprint().value(), asset.fingerprint().version(),
            asset.createdAt());
    }

    public static MediaAsset toDomain(MediaAssetJpaEntity entity) {
        return MediaAsset.restore(entity.getId(), entity.getOwnerId(), entity.getStorageKey(),
            ImageFormat.fromMimeType(entity.getMimeType()), entity.getByteSize(),
            entity.getWidth(), entity.getHeight(), entity.getRawSha256(),
            new ImageFingerprint(entity.getImageFingerprint(), entity.getFingerprintVersion()),
            entity.getCreatedAt());
    }
}
