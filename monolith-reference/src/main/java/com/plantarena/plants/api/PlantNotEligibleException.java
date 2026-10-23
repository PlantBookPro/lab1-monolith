package com.plantarena.plants.api;

import java.time.Instant;


public final class PlantNotEligibleException extends RuntimeException {

    private final Reason reason;
    private final Instant restrictedUntil;

    public PlantNotEligibleException(Reason reason, String message) {
        this(reason, message, null);
    }

    public PlantNotEligibleException(Reason reason, String message, Instant restrictedUntil) {
        super(message);
        this.reason = reason;
        this.restrictedUntil = restrictedUntil;
    }

    public Reason reason() {
        return reason;
    }

    
    public Instant restrictedUntil() {
        return restrictedUntil;
    }

    public enum Reason {
        NOT_OWNER, DEAD, RESTRICTED, NOT_RESERVABLE, NOT_APPROVED, NO_ACTIVE_RESERVATION
    }
}
