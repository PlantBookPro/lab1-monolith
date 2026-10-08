package com.plantarena.tournaments.application;


public final class SelfVoteForbiddenException extends RuntimeException {
    public SelfVoteForbiddenException(String message) {
        super(message);
    }
}
