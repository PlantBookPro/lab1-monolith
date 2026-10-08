package com.plantarena.media.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;


public interface DeleteMediaUseCase {

    void delete(CurrentActor actor, UUID assetId);
}
