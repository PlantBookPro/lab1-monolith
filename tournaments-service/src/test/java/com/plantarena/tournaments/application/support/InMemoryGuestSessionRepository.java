package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.domain.GuestSession;
import com.plantarena.tournaments.domain.GuestSessionRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Фейк репозитория гостевых сессий для application-тестов. */
public class InMemoryGuestSessionRepository implements GuestSessionRepository {

    private final Map<String, GuestSession> byTokenHash = new ConcurrentHashMap<>();

    @Override
    public GuestSession save(GuestSession session) {
        byTokenHash.put(session.tokenHash(), session);
        return session;
    }

    @Override
    public Optional<GuestSession> findByTokenHash(String tokenHash) {
        return Optional.ofNullable(byTokenHash.get(tokenHash));
    }
}
