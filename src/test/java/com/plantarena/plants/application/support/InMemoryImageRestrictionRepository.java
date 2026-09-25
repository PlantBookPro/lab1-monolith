package com.plantarena.plants.application.support;

import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.ImageRestrictionRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory фейк: append-only список запретов. */
public class InMemoryImageRestrictionRepository implements ImageRestrictionRepository {

    public final List<ImageRestriction> restrictions = new CopyOnWriteArrayList<>();

    @Override
    public ImageRestriction save(ImageRestriction restriction) {
        restrictions.add(restriction);
        return restriction;
    }

    @Override
    public List<ImageRestriction> findByOwnerAndFingerprint(UUID ownerId, String fingerprintValue) {
        return restrictions.stream()
            .filter(restriction -> restriction.ownerId().equals(ownerId)
                && restriction.fingerprint().value().equals(fingerprintValue))
            .toList();
    }
}
