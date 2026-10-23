package com.plantarena.plants.application;

import com.plantarena.plants.domain.ImageRestriction;
import com.plantarena.plants.domain.RestrictionKind;
import java.time.Instant;


public final class ImageRestrictedException extends RuntimeException {

    private final RestrictionKind kind;
    private final Instant retryAt;

    public ImageRestrictedException(ImageRestriction restriction) {
        super("Изображение запрещено: " + restriction.kind() + " (" + restriction.reason() + ")");
        this.kind = restriction.kind();
        this.retryAt = restriction.expiresAt(); 
    }

    public RestrictionKind kind() {
        return kind;
    }

    
    public Instant retryAt() {
        return retryAt;
    }
}
