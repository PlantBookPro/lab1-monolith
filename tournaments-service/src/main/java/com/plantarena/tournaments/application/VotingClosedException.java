package com.plantarena.tournaments.application;


public final class VotingClosedException extends RuntimeException {
    public VotingClosedException(String message) {
        super(message);
    }
}
