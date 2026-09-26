package com.plantarena.tournaments.application;

/** Тег используется турниром — удаление запрещено (раздел 13: 409). */
public class TagInUseException extends RuntimeException {

    public TagInUseException(String message) {
        super(message);
    }
}
