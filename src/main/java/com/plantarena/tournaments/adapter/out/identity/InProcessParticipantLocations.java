package com.plantarena.tournaments.adapter.out.identity;

import com.plantarena.identity.api.UserDirectory;
import com.plantarena.tournaments.application.port.out.ParticipantLocationsGateway;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;


@Component
public class InProcessParticipantLocations implements ParticipantLocationsGateway {

    private final UserDirectory userDirectory;

    public InProcessParticipantLocations(UserDirectory userDirectory) {
        this.userDirectory = userDirectory;
    }

    @Override
    public Optional<UserLocation> findLocation(UUID userId) {
        return userDirectory.findLocation(userId)
            .map(location -> new UserLocation(location.latitude(), location.longitude(),
                location.locationVersion()));
    }
}
