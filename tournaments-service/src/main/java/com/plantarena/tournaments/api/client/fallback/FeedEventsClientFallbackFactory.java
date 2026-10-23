package com.plantarena.tournaments.api.client.fallback;

import com.plantarena.tournaments.api.client.FeedEventsClient;
import org.springframework.stereotype.Component;

@Component
public class FeedEventsClientFallbackFactory extends AbstractClientFallbackFactory<FeedEventsClient> {

    public FeedEventsClientFallbackFactory(FeedEventsClientFallback fallback) {
        super(fallback);
    }
}
