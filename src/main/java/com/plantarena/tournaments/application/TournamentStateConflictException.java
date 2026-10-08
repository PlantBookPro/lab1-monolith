package com.plantarena.tournaments.application;


public class TournamentStateConflictException extends RuntimeException {

    public TournamentStateConflictException(String message) {
        super(message);
    }
}
