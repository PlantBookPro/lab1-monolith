package com.plantarena.plants.api;

/** Изображение уже активно зарезервировано другой заявкой (допущение 4 раздела 3). */
public final class ReservationConflictException extends RuntimeException {

    public ReservationConflictException(String message) {
        super(message);
    }
}
