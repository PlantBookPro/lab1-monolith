package com.plantarena.feed.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;


public record FeedCursor(long seed, Instant snapshotCutoff, Long lastSortKey, UUID lastId,
                         String subjectKey) {

    public FeedCursor {
        Objects.requireNonNull(snapshotCutoff, "snapshotCutoff");
        if (subjectKey == null || subjectKey.isBlank()) {
            throw new IllegalArgumentException("subjectKey обязательный");
        }
    }
}
