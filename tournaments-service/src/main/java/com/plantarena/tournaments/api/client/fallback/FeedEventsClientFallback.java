package com.plantarena.tournaments.api.client.fallback;

import com.plantarena.tournaments.api.client.FeedEventsClient;
import com.plantarena.tournaments.application.DependencyUnavailableException;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FeedEventsClientFallback implements FeedEventsClient {

    @Override
    public void windowOpened(WindowProjectionRequest request) {
        throw new DependencyUnavailableException("feed-service", null);
    }

    @Override
    public void windowClosed(UUID windowId) {
        throw new DependencyUnavailableException("feed-service", null);
    }
}
