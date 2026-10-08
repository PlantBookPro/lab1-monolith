package com.plantarena.tournaments.application.port.in;

import java.util.UUID;


public interface OnPlantModerationDecidedUseCase {

    void onPlantModerationDecided(UUID plantId, String decision);
}
