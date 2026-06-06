package com.plantarena.feed.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Курсор ленты (раздел 9): subjectKey обязателен, позиция keyset опциональна. */
@DisplayName("FeedCursor: валидация value object")
class FeedCursorTest {

    @Test
    @DisplayName("первая страница: lastSortKey/lastId отсутствуют; продолжение — заполнены")
    void валидный_курсор() {
        Instant cutoff = Instant.parse("2026-09-28T10:00:00Z");
        FeedCursor first = new FeedCursor(42L, cutoff, null, null, "USER:" + UUID.randomUUID());
        assertThat(first.seed()).isEqualTo(42L);
        FeedCursor next = new FeedCursor(42L, cutoff, -123L, UUID.randomUUID(),
            "GUEST:" + UUID.randomUUID());
        assertThat(next.lastSortKey()).isEqualTo(-123L);
    }

    @Test
    @DisplayName("без subjectKey курсор невозможен (не даёт прав на чужую ленту)")
    void без_субъекта() {
        assertThatThrownBy(() -> new FeedCursor(1L, Instant.now(), null, null, " "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeedCursor(1L, null, null, null, "USER:x"))
            .isInstanceOf(NullPointerException.class);
    }
}
