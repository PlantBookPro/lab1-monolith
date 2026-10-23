package com.plantarena.plants.application.port.in;

import com.plantarena.plants.api.PlantModerationStatus;
import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;


public interface GetPlantModerationUseCase {

    ModerationStatusResult moderation(CurrentActor actor, UUID plantId);

    record ModerationStatusResult(PlantModerationStatus moderationStatus, String reason,
                                  boolean retryUploadAllowed) {
    }
}
