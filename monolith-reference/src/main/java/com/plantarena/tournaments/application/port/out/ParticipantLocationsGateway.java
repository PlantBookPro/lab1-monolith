package com.plantarena.tournaments.application.port.out;

import java.util.Optional;
import java.util.UUID;


public interface ParticipantLocationsGateway {

    
    Optional<UserLocation> findLocation(UUID userId);

    
    record UserLocation(double latitude, double longitude, long locationVersion) {
    }
}
