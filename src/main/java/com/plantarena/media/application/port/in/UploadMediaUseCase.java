package com.plantarena.media.application.port.in;

import com.plantarena.media.application.MediaAssetResult;
import com.plantarena.shared.security.CurrentActor;

/**
 * Загрузка файла (раздел 6): идентифицированный пользователь, multipart-байты →
 * валидация фактического формата/размеров → хранилище → метаданные.
 */
public interface UploadMediaUseCase {

    MediaAssetResult upload(CurrentActor actor, byte[] content);
}
