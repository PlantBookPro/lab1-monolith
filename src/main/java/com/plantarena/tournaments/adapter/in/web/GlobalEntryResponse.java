package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.tournaments.application.port.in.SubmitGlobalEntryUseCase;
import java.time.Instant;
import java.util.UUID;

/** Глобальное участие (раздел 13). */
public record GlobalEntryResponse(UUID id, UUID plantId, String status, Instant joinedAt) {

    static GlobalEntryResponse from(SubmitGlobalEntryUseCase.GlobalEntryView view) {
        return new GlobalEntryResponse(view.id(), view.plantId(), view.status(),
            view.joinedAt());
    }
}
