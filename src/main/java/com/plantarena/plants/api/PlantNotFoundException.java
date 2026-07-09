package com.plantarena.plants.api;

/** Растение не найдено или скрыто политикой приватности (404, раздел 13). */
public final class PlantNotFoundException extends RuntimeException {

    public PlantNotFoundException(String message) {
        super(message);
    }
}
