package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Доменная политика повторного использования изображения (раздел 6,
 * допущения 2–3): постоянный запрет имеет приоритет над временным;
 * из нескольких COOLDOWN действует самый поздний expiresAt; истёкший
 * COOLDOWN не блокирует. Чистая функция без состояния.
 */
public final class ImageReusePolicy {

    /** Действующий активный запрет для пары (ownerId, fingerprint) или empty. */
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
