package com.plantarena.tournaments.application;


public final class LocationRequiredException extends RuntimeException {

    public LocationRequiredException(String message) {
        super(message);
    }
}
