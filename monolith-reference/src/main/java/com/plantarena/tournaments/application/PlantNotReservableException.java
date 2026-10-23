package com.plantarena.tournaments.application;

import java.time.Instant;


public class PlantNotReservableException extends RuntimeException {

    private final Instant retryAt;

    public PlantNotReservableException(String message, Instant retryAt) {
        super(message);
        this.retryAt = retryAt;
    }

    public Instant retryAt() {
        return retryAt;
    }
}
