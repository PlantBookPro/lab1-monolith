package com.plantarena.moderation.application.support;

import com.plantarena.moderation.application.port.out.MediaContentGateway;
import java.util.Optional;
import java.util.UUID;

/** Фейк байтов файла: preset-байты или «файл исчез». */
public class FakeMediaContentGateway implements MediaContentGateway {

    public byte[] bytes = new byte[] {1, 2, 3};
    public boolean present = true;

    @Override
    public Optional<MediaContent> loadContent(UUID assetId) {
        return present ? Optional.of(new MediaContent(bytes, "image/png")) : Optional.empty();
    }
}
