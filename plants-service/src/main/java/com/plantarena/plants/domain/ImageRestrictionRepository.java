package com.plantarena.plants.domain;

import java.util.List;
import java.util.UUID;


public interface ImageRestrictionRepository {

    ImageRestriction save(ImageRestriction restriction);

    List<ImageRestriction> findByOwnerAndFingerprint(UUID ownerId, String fingerprintValue);
}
