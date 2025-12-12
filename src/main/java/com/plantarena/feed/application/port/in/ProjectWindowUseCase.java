package com.plantarena.feed.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Проекция ленты (ADR-002): применение событий окна (перевод события в команду feed). */
public interface ProjectWindowUseCase {

    void onWindowOpened(WindowCardsCommand command);

    void onWindowClosed(UUID windowId);

    record WindowCardsCommand(UUID windowId, UUID tournamentId, String scope, UUID clusterId,
                              Instant closesAt, List<CardSeed> cards) {
    }

    record CardSeed(UUID entryId, UUID userId, UUID plantId, Instant joinedAt) {
    }
}
