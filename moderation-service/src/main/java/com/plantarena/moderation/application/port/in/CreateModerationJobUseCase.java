package com.plantarena.moderation.application.port.in;

import java.util.UUID;


public interface CreateModerationJobUseCase {

    void onPlantSubmitted(UUID plantId, UUID assetId);
}
