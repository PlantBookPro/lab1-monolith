package com.plantarena.tournaments.application.port.in;

import com.plantarena.shared.security.CurrentActor;
import java.time.Instant;
import java.util.UUID;

/** Подача заявки в глобальный турнир (раздел 8, алгоритм 1). */
public interface SubmitGlobalEntryUseCase {

    GlobalEntryView submit(CurrentActor actor, UUID plantId);

    /** Созданное глобальное участие. */
    record GlobalEntryView(UUID id, UUID plantId, String status, Instant joinedAt) {
    }
}
