package com.plantarena.moderation.adapter.out.media;

import com.plantarena.moderation.api.client.MediaContentClient;
import com.plantarena.moderation.application.port.out.MediaContentGateway;
import feign.FeignException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class HttpMediaContentGateway implements MediaContentGateway {

    private final MediaContentClient mediaClient;

    @Override
    public Optional<MediaContent> loadContent(UUID assetId) {
        try {
            ResponseEntity<byte[]> response = mediaClient.getContent(assetId);
            return Optional.of(new MediaContent(response.getBody(),
                response.getHeaders().getContentType().toString()));
        } catch (FeignException.NotFound e) {
            return Optional.empty();
        }
    }
}
