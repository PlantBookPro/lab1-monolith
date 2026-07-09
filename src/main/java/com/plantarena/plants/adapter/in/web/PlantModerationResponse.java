package com.plantarena.plants.adapter.in.web;

import com.plantarena.plants.api.PlantModerationStatus;
import com.plantarena.plants.application.port.in.GetPlantModerationUseCase;

/** Ответ GET /plants/{id}/moderation: статус, причина, разрешён ли повторный upload. */
public record PlantModerationResponse(PlantModerationStatus moderationStatus, String reason,
                                      boolean retryUploadAllowed) {

    public static PlantModerationResponse from(GetPlantModerationUseCase.ModerationStatusResult result) {
        return new PlantModerationResponse(result.moderationStatus(), result.reason(),
            result.retryUploadAllowed());
    }
}
