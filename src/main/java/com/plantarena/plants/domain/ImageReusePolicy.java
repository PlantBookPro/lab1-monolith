package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;


public final class ImageReusePolicy {

    
    public Optional<ImageRestriction> activeRestriction(List<ImageRestriction> restrictions,
                                                        Instant now) {
        Optional<ImageRestriction> permanent = restrictions.stream()
            .filter(restriction -> restriction.kind() == RestrictionKind.PERMANENT)
            .findFirst();
        if (permanent.isPresent()) {
            return permanent;
        }
        return restrictions.stream()
            .filter(restriction -> restriction.kind() == RestrictionKind.COOLDOWN)
            .filter(restriction -> now.isBefore(restriction.expiresAt()))
            .max(Comparator.comparing(ImageRestriction::expiresAt));
    }
}
