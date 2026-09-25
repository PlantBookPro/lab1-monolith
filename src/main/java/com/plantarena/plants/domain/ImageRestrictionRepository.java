package com.plantarena.plants.domain;

import java.util.List;
import java.util.UUID;

/** Порт репозитория запретов: append-only, поиск по паре (ownerId, fingerprint). */
public interface ImageRestrictionRepository {

    ImageRestriction save(ImageRestriction restriction);

    List<ImageRestriction> findByOwnerAndFingerprint(UUID ownerId, String fingerprintValue);
}
