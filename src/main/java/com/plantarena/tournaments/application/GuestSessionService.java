package com.plantarena.tournaments.application;

import com.plantarena.tournaments.application.port.in.CreateGuestSessionUseCase;
import com.plantarena.tournaments.application.port.out.AbuseSignals;
import com.plantarena.tournaments.domain.GuestSession;
import com.plantarena.tournaments.domain.GuestSessionRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class GuestSessionService implements CreateGuestSessionUseCase {

    private final GuestSessionRepository sessions;
    private final GuestSessionsSettings settings;
    private final FixedWindowRateLimiter rateLimiter;
    private final AbuseSignals abuseSignals;
    private final Clock clock;

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
