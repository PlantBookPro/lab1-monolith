package com.plantarena.tournaments.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Гостевая сессия (раздел 9): хранится только хэш токена, срок действия. */
@DisplayName("GuestSession: хэш токена и срок действия")
class GuestSessionTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final String HASH_64 = "a".repeat(64);

    @Test
    @DisplayName("выдача: хэш и срок now + ttl; токена в агрегате нет вообще")
    void выдача_сессии() {
        UUID id = UUID.randomUUID();
        GuestSession session = GuestSession.issue(id, HASH_64, NOW, Duration.ofHours(24));
        assertThat(session.id()).isEqualTo(id);
        assertThat(session.tokenHash()).isEqualTo(HASH_64);
        assertThat(session.createdAt()).isEqualTo(NOW);
        assertThat(session.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("активна, пока now < expiresAt; в expiresAt — уже нет (полуинтервал, как окна)")
    void активность() {
        GuestSession session = GuestSession.issue(UUID.randomUUID(), HASH_64, NOW,
            Duration.ofHours(1));
        assertThat(session.isActive(NOW)).isTrue();
        assertThat(session.isActive(NOW.plus(Duration.ofMinutes(59)))).isTrue();
        assertThat(session.isActive(session.expiresAt())).isFalse();
        assertThat(session.isActive(NOW.plus(Duration.ofHours(2)))).isFalse();
    }

    @Test
    @DisplayName("некорректные аргументы: пустой хэш, неположительный ttl")
    void валидация() {
        assertThatThrownBy(() -> GuestSession.issue(UUID.randomUUID(), "", NOW,
            Duration.ofHours(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuestSession.issue(UUID.randomUUID(), HASH_64, NOW,
            Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GuestSession.issue(UUID.randomUUID(), HASH_64, NOW,
            Duration.ofHours(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
