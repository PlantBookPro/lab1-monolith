package com.plantarena.tournaments.application;


public class TagInUseException extends RuntimeException {

    public TagInUseException(String message) {
        super(message);
    }
}
