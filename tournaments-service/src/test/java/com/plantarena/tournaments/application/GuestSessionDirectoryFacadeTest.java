package com.plantarena.tournaments.application;

import com.plantarena.tournaments.application.support.InMemoryGuestSessionRepository;
import com.plantarena.tournaments.domain.GuestSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Фасад read-контракта feed (раздел 9): активная сессия по сырому токену. */
@DisplayName("GuestSessionDirectoryFacade: токен → активная сессия")
class GuestSessionDirectoryFacadeTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private final InMemoryGuestSessionRepository sessions = new InMemoryGuestSessionRepository();
    private final GuestSessionDirectoryFacade facade = new GuestSessionDirectoryFacade(
        sessions, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("активная сессия: хэш совпал и срок не истёк")
    void активная_сессия() {
        String token = GuestTokens.newToken();
        UUID id = UUID.randomUUID();
        sessions.save(GuestSession.issue(id, GuestTokens.sha256Hex(token), NOW,
            Duration.ofHours(1)));

        assertThat(facade.activeSessionId(token)).contains(id);
    }

    @Test
    @DisplayName("истёкшая/неизвестная/пустая — пусто")
    void неактивные() {
        String token = GuestTokens.newToken();
        sessions.save(GuestSession.issue(UUID.randomUUID(), GuestTokens.sha256Hex(token),
            NOW.minus(Duration.ofHours(2)), Duration.ofHours(1))); // истёкшая

        assertThat(facade.activeSessionId(token)).isEmpty();
        assertThat(facade.activeSessionId(GuestTokens.newToken())).isEmpty();
        assertThat(facade.activeSessionId(" ")).isEmpty();
        assertThat(facade.activeSessionId(null)).isEmpty();
    }
}
