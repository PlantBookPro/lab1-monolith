package com.plantarena.moderation.adapter.out.media;

import com.plantarena.media.api.MediaAssets;
import com.plantarena.moderation.application.port.out.MediaContentGateway;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class InProcessMediaContentGateway implements MediaContentGateway {

    private final MediaAssets mediaAssets;

    @Override
    public Optional<MediaContent> loadContent(UUID assetId) {
        return mediaAssets.loadContent(assetId)
            .map(content -> new MediaContent(content.content(), content.mimeType()));
    }
}
