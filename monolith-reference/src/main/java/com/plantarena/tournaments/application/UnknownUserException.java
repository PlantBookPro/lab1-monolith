package com.plantarena.tournaments.application;


public class UnknownUserException extends RuntimeException {

    public UnknownUserException(String message) {
        super(message);
    }
}
