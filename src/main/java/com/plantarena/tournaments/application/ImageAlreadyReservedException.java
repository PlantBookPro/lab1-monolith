package com.plantarena.tournaments.application;

/** Изображение уже активно зарезервировано другой заявкой (раздел 6, 409). */
public class ImageAlreadyReservedException extends RuntimeException {

    public ImageAlreadyReservedException(String message) {
        super(message);
    }
}
