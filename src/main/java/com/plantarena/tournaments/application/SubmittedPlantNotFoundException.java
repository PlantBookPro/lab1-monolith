package com.plantarena.tournaments.application;

/** 404 PLANT_NOT_FOUND. */
public final class SubmittedPlantNotFoundException extends RuntimeException {

    public SubmittedPlantNotFoundException(String message) {
        super(message);
    }
}
