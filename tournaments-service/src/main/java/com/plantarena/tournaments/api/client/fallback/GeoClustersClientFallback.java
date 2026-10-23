package com.plantarena.tournaments.api.client.fallback;

import com.plantarena.tournaments.api.client.GeoClustersClient;
import com.plantarena.tournaments.application.DependencyUnavailableException;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GeoClustersClientFallback implements GeoClustersClient {

    @Override
    public List<AssignedClusterView> assign(AssignmentRequest request) {
        throw new DependencyUnavailableException("geo-service", null);
    }
}
