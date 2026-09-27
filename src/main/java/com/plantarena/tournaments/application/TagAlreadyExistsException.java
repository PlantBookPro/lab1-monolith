package com.plantarena.tournaments.application;

/** Тег с таким именем уже существует (раздел 13: 409). */
public class TagAlreadyExistsException extends RuntimeException {

    public TagAlreadyExistsException(String message) {
        super(message);
    }
}
