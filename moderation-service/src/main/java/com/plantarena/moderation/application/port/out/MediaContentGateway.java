package com.plantarena.moderation.application.port.out;

import java.util.Optional;
import java.util.UUID;


public interface MediaContentGateway {

    Optional<MediaContent> loadContent(UUID assetId);

    record MediaContent(byte[] bytes, String mimeType) {
    }
}
