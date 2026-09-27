package com.plantarena.tournaments.application;

/** Неизвестное значение голоса — допустимо LIKE/DISLIKE (400). */
public final class UnknownVoteValueException extends RuntimeException {
    public UnknownVoteValueException(String message) {
        super(message);
    }
}
