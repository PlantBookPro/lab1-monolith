package com.plantarena.tournaments.application;


public class TournamentNotFoundException extends RuntimeException {

    public TournamentNotFoundException(String message) {
        super(message);
    }
}
