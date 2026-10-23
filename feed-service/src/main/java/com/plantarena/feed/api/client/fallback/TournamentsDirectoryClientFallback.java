package com.plantarena.feed.api.client.fallback;

import com.plantarena.feed.api.client.TournamentsDirectoryClient;
import com.plantarena.feed.application.DependencyUnavailableException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TournamentsDirectoryClientFallback implements TournamentsDirectoryClient {

    @Override
    public Set<UUID> votedEntryIds(String subjectKey) {
        throw new DependencyUnavailableException("tournaments-service", null);
    }

    @Override
    public Set<UUID> participatedTournamentIds(UUID userId) {
        throw new DependencyUnavailableException("tournaments-service", null);
    }

    @Override
    public ActiveSessionResponse activeSession(String rawToken) {
        throw new DependencyUnavailableException("tournaments-service", null);
    }
}
