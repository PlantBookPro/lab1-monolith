package com.plantarena.feed.application.port.out;

import java.util.Optional;
import java.util.UUID;


public interface OwnerDirectory {

    Optional<OwnerView> findOwner(UUID userId);

    record OwnerView(UUID userId, String displayName) {
    }
}
