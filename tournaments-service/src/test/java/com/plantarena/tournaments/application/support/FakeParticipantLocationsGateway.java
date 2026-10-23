package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.application.port.out.ParticipantLocationsGateway;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Фейк порта координат: координаты задаются тестом. */
public class FakeParticipantLocationsGateway implements ParticipantLocationsGateway {

    public final Map<UUID, UserLocation> locations = new HashMap<>();

    @Override
    public Optional<UserLocation> findLocation(UUID userId) {
        return Optional.ofNullable(locations.get(userId));
    }
}
