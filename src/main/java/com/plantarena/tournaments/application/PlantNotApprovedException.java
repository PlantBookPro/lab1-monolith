package com.plantarena.tournaments.application;

/** 409 PLANT_NOT_APPROVED. */
public final class PlantNotApprovedException extends RuntimeException {

    public PlantNotApprovedException(String message) {
        super(message);
    }
}
