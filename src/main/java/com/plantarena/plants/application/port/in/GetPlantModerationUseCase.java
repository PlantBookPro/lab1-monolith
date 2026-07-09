package com.plantarena.plants.application.port.in;

import com.plantarena.plants.api.PlantModerationStatus;
import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/** Статус модерации для владельца (раздел 13: GET /plants/{id}/moderation). */
public interface GetPlantModerationUseCase {

    ModerationStatusResult moderation(CurrentActor actor, UUID plantId);

    record ModerationStatusResult(PlantModerationStatus moderationStatus, String reason,
                                  boolean retryUploadAllowed) {
    }
}
