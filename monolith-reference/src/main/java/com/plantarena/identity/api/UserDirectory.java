package com.plantarena.identity.api;

import java.util.Optional;
import java.util.UUID;


public interface UserDirectory {

    Optional<UserData> findById(UUID userId);

    
    Optional<UserLocation> findLocation(UUID userId);

    
    record UserData(UUID id, String displayName, boolean active) {
    }

    
    record UserLocation(double latitude, double longitude, long locationVersion) {
    }
}
