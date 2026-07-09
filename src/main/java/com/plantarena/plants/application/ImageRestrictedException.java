package com.plantarena.plants.application;

import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.RestrictionKind;
import java.time.Instant;

/** Изображение запрещено для повторного использования (раздел 13: 409 + retryAt). */
public final class ImageRestrictedException extends RuntimeException {

    private final RestrictionKind kind;
    private final Instant retryAt;

    public ImageRestrictedException(ImageRestriction restriction) {
        super("Изображение запрещено: " + restriction.kind() + " (" + restriction.reason() + ")");
        this.kind = restriction.kind();
        this.retryAt = restriction.expiresAt(); // null для PERMANENT
    }

    public RestrictionKind kind() {
        return kind;
    }

    /** Момент, когда COOLDOWN истечёт; null для PERMANENT (раздел 13). */
    public Instant retryAt() {
        return retryAt;
    }
}
