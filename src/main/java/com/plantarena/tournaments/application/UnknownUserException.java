package com.plantarena.tournaments.application;

/** Пользователь приглашения неизвестен identity (раздел 13: 404). */
public class UnknownUserException extends RuntimeException {

    public UnknownUserException(String message) {
        super(message);
    }
}
