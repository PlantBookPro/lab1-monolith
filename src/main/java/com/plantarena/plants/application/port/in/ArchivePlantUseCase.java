package com.plantarena.plants.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.util.UUID;


public interface ArchivePlantUseCase {

    void archive(CurrentActor actor, UUID plantId);
}
