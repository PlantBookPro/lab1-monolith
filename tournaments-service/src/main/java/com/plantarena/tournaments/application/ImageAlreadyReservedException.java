package com.plantarena.tournaments.application;


public class ImageAlreadyReservedException extends RuntimeException {

    public ImageAlreadyReservedException(String message) {
        super(message);
    }
}
