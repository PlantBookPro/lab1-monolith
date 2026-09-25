package com.plantarena.media.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/**
 * Скачивание изображения (раздел 13): приватные незаявленные файлы —
 * только владельцу (видимость по растениям — итерация 3).
 */
public interface DownloadMediaUseCase {

    DownloadedMedia download(CurrentActor actor, UUID assetId);

    record DownloadedMedia(UUID assetId, String mimeType, byte[] content) {}
}
