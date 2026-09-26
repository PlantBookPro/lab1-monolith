package com.plantarena.moderation.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * ACL-порт moderation к байтам файла (media.api.MediaAssets.loadContent).
 * Потребитель владеет типами; storageKey не пересекает границу media.
 */
public interface MediaContentGateway {

    Optional<MediaContent> loadContent(UUID assetId);

    record MediaContent(byte[] bytes, String mimeType) {
    }
}
