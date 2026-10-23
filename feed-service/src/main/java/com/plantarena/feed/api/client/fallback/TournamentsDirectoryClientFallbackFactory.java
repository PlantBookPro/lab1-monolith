package com.plantarena.feed.api.client.fallback;

import com.plantarena.feed.api.client.TournamentsDirectoryClient;
import org.springframework.stereotype.Component;

@Component
public class TournamentsDirectoryClientFallbackFactory extends AbstractClientFallbackFactory<TournamentsDirectoryClient> {

    public TournamentsDirectoryClientFallbackFactory(TournamentsDirectoryClientFallback fallback) {
        super(fallback);
    }
}
