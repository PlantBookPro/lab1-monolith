package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.time.Instant;
import java.util.UUID;


public interface SubmitGlobalEntryUseCase {

    GlobalEntryView submit(CurrentActor actor, UUID plantId);

    
    record GlobalEntryView(UUID id, UUID plantId, String status, Instant joinedAt) {
    }
}
