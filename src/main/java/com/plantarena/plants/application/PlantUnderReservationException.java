package com.plantarena.plants.application;

/** Растение под активным резервом — архивация запрещена (раздел 13: 409). */
public final class PlantUnderReservationException extends RuntimeException {

    public PlantUnderReservationException(String message) {
        super(message);
    }
}
