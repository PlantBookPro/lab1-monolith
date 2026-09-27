package com.plantarena.tournaments.application;

/** Растение подачи не найдено (раздел 13: 404). */
public class InvitedPlantNotFoundException extends RuntimeException {

    public InvitedPlantNotFoundException(String message) {
        super(message);
    }
}
