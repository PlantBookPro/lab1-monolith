package com.plantarena.tournaments.application;

import com.plantarena.tournaments.application.port.in.CreateGuestSessionUseCase;
import com.plantarena.tournaments.application.support.InMemoryGuestSessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Выдача гостевых сессий (раздел 9): токен один раз, в репозитории — только
 * хэш; лимит выдачи по IP — 429 с retryAfter; сигнал о превышении — в AbuseSignals.
 */
@DisplayName("GuestSessionService: токен, хэш, лимит 429")
class GuestSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private final InMemoryGuestSessionRepository sessions = new InMemoryGuestSessionRepository();
    private final FixedWindowRateLimiter rateLimiter = new FixedWindowRateLimiter(
        Clock.fixed(NOW, ZoneOffset.UTC));
    private final GuestSessionsSettings settings = new GuestSessionsSettings(
        Duration.ofHours(24), 2, 30);
    private final RecordingAbuseSignals abuseSignals = new RecordingAbuseSignals();
    private final GuestSessionService service = new GuestSessionService(sessions, settings,
        rateLimiter, abuseSignals, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("выдача: токен и expiresAt наружу; в репозитории — только хэш токена")
    void выдача_токена() {
        CreateGuestSessionUseCase.GuestSessionIssued issued = service.issue("127.0.0.1");

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(sessions.findByTokenHash(GuestTokens.sha256Hex(issued.token())))
            .as("в репозитории только хэш, не токен")
            .isPresent();
    }

    @Test
    @DisplayName("токены непредсказуемы: две выдачи — разные токены и хэши")
    void непредсказуемость() {
        String token1 = service.issue("127.0.0.1").token();
        String token2 = service.issue("127.0.0.1").token();
        assertThat(token1).isNotEqualTo(token2);
        assertThat(GuestTokens.sha256Hex(token1)).isNotEqualTo(GuestTokens.sha256Hex(token2));
    }

    @Test
    @DisplayName("лимит выдачи на IP: третий в минуту — RateLimitExceededException с retryAfter")
    void лимит_выдачи() {
        service.issue("127.0.0.1");
        service.issue("127.0.0.1");

        assertThatThrownBy(() -> service.issue("127.0.0.1"))
            .isInstanceOf(RateLimitExceededException.class)
            .satisfies(e -> assertThat(((RateLimitExceededException) e).retryAfterSeconds())
                .isBetween(1L, 60L));
        assertThat(abuseSignals.lastAction).isEqualTo("guest-session-limit");

        // другой IP — своя корзина
        assertThat(service.issue("192.168.0.1").token()).isNotBlank();
    }

    private static final class RecordingAbuseSignals
            implements com.plantarena.tournaments.application.port.out.AbuseSignals {

        private String lastAction;

        @Override
        public void signal(String action, String details) {
            this.lastAction = action;
        }
    }
}
