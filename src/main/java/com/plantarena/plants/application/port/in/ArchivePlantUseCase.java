package com.plantarena.plants.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;

/** Архивация владельцем вне активного резерва (раздел 13: DELETE /plants/{id}). */
public interface ArchivePlantUseCase {

    void archive(CurrentActor actor, UUID plantId);
}
