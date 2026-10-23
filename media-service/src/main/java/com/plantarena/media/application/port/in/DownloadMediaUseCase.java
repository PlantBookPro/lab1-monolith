package com.plantarena.media.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;


public interface DownloadMediaUseCase {

    DownloadedMedia download(CurrentActor actor, UUID assetId);

    record DownloadedMedia(UUID assetId, String mimeType, byte[] content) {}
}
