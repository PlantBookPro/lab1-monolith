package com.plantarena.media.application.port.in;

import com.plantarena.media.application.MediaAssetResult;
import com.plantarena.shared.security.CurrentActor;


public interface UploadMediaUseCase {

    MediaAssetResult upload(CurrentActor actor, byte[] content);
}
