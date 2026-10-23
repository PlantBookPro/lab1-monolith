package com.plantarena.tournaments.api.client.fallback;

import com.plantarena.tournaments.api.client.IdentityDirectoryClient;
import com.plantarena.tournaments.application.DependencyUnavailableException;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class IdentityDirectoryClientFallback implements IdentityDirectoryClient {

    @Override
    public ActorView getActor(UUID userId) {
        throw new DependencyUnavailableException("identity-service", null);
    }

    @Override
    public UserProfileView getProfile(UUID userId) {
        throw new DependencyUnavailableException("identity-service", null);
    }

    @Override
    public UserLocationView getLocation(UUID userId) {
        throw new DependencyUnavailableException("identity-service", null);
    }
}
