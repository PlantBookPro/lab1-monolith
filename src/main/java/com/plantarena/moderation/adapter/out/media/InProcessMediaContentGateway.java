package com.plantarena.moderation.adapter.out.media;

import com.plantarena.media.api.MediaAssets;
import com.plantarena.moderation.application.port.out.MediaContentGateway;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL-адаптер media → moderation (раздел 4.3, Customer–Supplier): байты файла
 * через опубликованный контракт media.api; storageKey не пересекает границу.
 * Права не проверяются: вызов внутреннего контракта монолита по assetId из
 * задания модерации. В лабе №2 меняется на HTTP-клиент file-service.
 */
@Component
public class InProcessMediaContentGateway implements MediaContentGateway {

    private final MediaAssets mediaAssets;

    public InProcessMediaContentGateway(MediaAssets mediaAssets) {
        this.mediaAssets = mediaAssets;
    }

    @Override
    public Optional<MediaContent> loadContent(UUID assetId) {
        return mediaAssets.loadContent(assetId)
            .map(content -> new MediaContent(content.content(), content.mimeType()));
    }
}
