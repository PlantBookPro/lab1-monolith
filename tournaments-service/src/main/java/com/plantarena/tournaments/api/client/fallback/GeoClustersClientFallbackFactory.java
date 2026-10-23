package com.plantarena.tournaments.api.client.fallback;

import com.plantarena.tournaments.api.client.GeoClustersClient;
import org.springframework.stereotype.Component;

@Component
public class GeoClustersClientFallbackFactory extends AbstractClientFallbackFactory<GeoClustersClient> {

    public GeoClustersClientFallbackFactory(GeoClustersClientFallback fallback) {
        super(fallback);
    }
}
