package com.plantarena.tournaments.adapter.out.identity;

import com.plantarena.identity.api.UserDirectory;
import com.plantarena.tournaments.application.port.out.ParticipantLocationsGateway;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class InProcessParticipantLocations implements ParticipantLocationsGateway {

    private final UserDirectory userDirectory;

    @Override
    public Optional<UserLocation> findLocation(UUID userId) {
        return userDirectory.findLocation(userId)
            .map(location -> new UserLocation(location.latitude(), location.longitude(),
                location.locationVersion()));
    }
}
