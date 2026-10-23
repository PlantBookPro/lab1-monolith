package com.plantarena.tournaments.api.client.fallback;

import com.plantarena.tournaments.api.client.PlantsDirectoryClient;
import org.springframework.stereotype.Component;

@Component
public class PlantsDirectoryClientFallbackFactory extends AbstractClientFallbackFactory<PlantsDirectoryClient> {

    public PlantsDirectoryClientFallbackFactory(PlantsDirectoryClientFallback fallback) {
        super(fallback);
    }
}
