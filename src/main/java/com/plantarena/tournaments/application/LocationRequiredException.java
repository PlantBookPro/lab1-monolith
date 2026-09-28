package com.plantarena.tournaments.application;

/** 409 LOCATION_REQUIRED. */
public final class LocationRequiredException extends RuntimeException {

    public LocationRequiredException(String message) {
        super(message);
    }
}
