package com.plantarena.tournaments.application;

import com.plantarena.tournaments.api.GuestSessionDirectory;
import com.plantarena.tournaments.domain.GuestSession;
import com.plantarena.tournaments.domain.GuestSessionRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Фасад read-контракта feed (раздел 9): хэш токена + срок действия. */
@Service
public class GuestSessionDirectoryFacade implements GuestSessionDirectory {

    private final GuestSessionRepository sessions;
    private final Clock clock;

    public GuestSessionDirectoryFacade(GuestSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> activeSessionId(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return sessions.findByTokenHash(GuestTokens.sha256Hex(rawToken.trim()))
            .filter(session -> session.isActive(clock.instant()))
            .map(GuestSession::id);
    }
}
