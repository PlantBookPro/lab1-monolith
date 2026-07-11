package com.plantarena.tournaments.application;

import com.plantarena.tournaments.application.port.in.CreateGuestSessionUseCase;
import com.plantarena.tournaments.application.port.out.AbuseSignals;
import com.plantarena.tournaments.domain.GuestSession;
import com.plantarena.tournaments.domain.GuestSessionRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Выдача гостевых сессий (раздел 9): публично, токен возвращается один раз;
 * в БД — только SHA-256 хэш. Лимит выдачи — на IP (фиксированное окно),
 * превышение — 429 + сигнал в AbuseSignals.
 */
@Service
public class GuestSessionService implements CreateGuestSessionUseCase {

    private final GuestSessionRepository sessions;
    private final GuestSessionsSettings settings;
    private final FixedWindowRateLimiter rateLimiter;
    private final AbuseSignals abuseSignals;
    private final Clock clock;

    public GuestSessionService(GuestSessionRepository sessions, GuestSessionsSettings settings,
                               FixedWindowRateLimiter rateLimiter, AbuseSignals abuseSignals,
                               Clock clock) {
        this.sessions = sessions;
        this.settings = settings;
        this.rateLimiter = rateLimiter;
        this.abuseSignals = abuseSignals;
        this.clock = clock;
    }

    @Override
    @Transactional
    public GuestSessionIssued issue(String clientIp) {
        String ip = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        try {
            rateLimiter.check("guest-session:" + ip, settings.sessionCreationLimitPerMinute());
        } catch (RateLimitExceededException e) {
            abuseSignals.signal("guest-session-limit", "ip=" + ip);
            throw e;
        }
        String token = GuestTokens.newToken();
        GuestSession session = GuestSession.issue(UUID.randomUUID(),
            GuestTokens.sha256Hex(token), clock.instant(), settings.sessionTtl());
        sessions.save(session);
        return new GuestSessionIssued(token, session.expiresAt());
    }
}
