package com.plantarena.moderation.application.port.in;

import java.util.UUID;

/** Реакция на PlantSubmitted: создать задание распознавания (идемпотентно). */
public interface CreateModerationJobUseCase {

    void onPlantSubmitted(UUID plantId, UUID assetId);
}
