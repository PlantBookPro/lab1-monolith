package com.plantarena.tournaments.adapter.out.identity;

import com.plantarena.tournaments.api.client.IdentityDirectoryClient;
import com.plantarena.tournaments.application.port.out.ParticipantLocationsGateway;
import feign.FeignException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpParticipantLocations implements ParticipantLocationsGateway {

    private final IdentityDirectoryClient identityClient;

    @Override
    public Optional<UserLocation> findLocation(UUID userId) {
        try {
            return Optional.of(identityClient.getLocation(userId))
                .map(location -> new UserLocation(location.latitude(), location.longitude(),
                    location.locationVersion()));
        } catch (FeignException.NotFound e) {
            return Optional.empty();
        }
    }
}
