package com.plantarena.feed.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Курсор ленты (раздел 9): seed, snapshotCutoff (новые карточки после него
 * исключаются), keyset-позиция (lastSortKey/lastId; null на первой странице)
 * и субъект — курсор не даёт прав на чужую ленту. Подписывается HMAC
 * (FeedCursorCodec, application).
 */
public record FeedCursor(long seed, Instant snapshotCutoff, Long lastSortKey, UUID lastId,
                         String subjectKey) {

    public FeedCursor {
        Objects.requireNonNull(snapshotCutoff, "snapshotCutoff");
        if (subjectKey == null || subjectKey.isBlank()) {
            throw new IllegalArgumentException("subjectKey обязательный");
        }
    }
}
