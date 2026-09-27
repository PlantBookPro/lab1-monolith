package com.plantarena.tournaments.application.port.in;

import java.util.UUID;

/**
 * Реакция на PlantModerationDecided (раздел 4.3): APPROVED → заявка READY
 * (если дедлайн не прошёл и резерв действителен), REJECTED → возврат в
 * INVITED с освобождением резерва. Вызывается синхронно в tx применения
 * решения модерации (раздел 10.3, ADR-010).
 */
public interface OnPlantModerationDecidedUseCase {

    void onPlantModerationDecided(UUID plantId, String decision);
}
