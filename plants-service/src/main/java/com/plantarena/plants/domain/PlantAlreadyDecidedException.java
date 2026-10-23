package com.plantarena.plants.domain;


public final class PlantAlreadyDecidedException extends RuntimeException {

    public PlantAlreadyDecidedException(String message) {
        super(message);
    }
}
