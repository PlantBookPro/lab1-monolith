package com.plantarena.plants.api;


public final class PlantNotFoundException extends RuntimeException {

    public PlantNotFoundException(String message) {
        super(message);
    }
}
