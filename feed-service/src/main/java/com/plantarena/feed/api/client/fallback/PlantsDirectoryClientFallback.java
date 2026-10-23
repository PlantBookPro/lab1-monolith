package com.plantarena.feed.api.client.fallback;

import com.plantarena.feed.api.client.PlantsDirectoryClient;
import com.plantarena.feed.application.DependencyUnavailableException;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PlantsDirectoryClientFallback implements PlantsDirectoryClient {

    @Override
    public PlantView getPlant(UUID plantId) {
        throw new DependencyUnavailableException("plants-service", null);
    }
}
