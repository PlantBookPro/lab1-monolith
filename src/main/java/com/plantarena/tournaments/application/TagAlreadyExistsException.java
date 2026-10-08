package com.plantarena.tournaments.application;


public class TagAlreadyExistsException extends RuntimeException {

    public TagAlreadyExistsException(String message) {
        super(message);
    }
}
