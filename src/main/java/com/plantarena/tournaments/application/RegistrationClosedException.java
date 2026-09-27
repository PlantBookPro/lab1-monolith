package com.plantarena.tournaments.application;

/** Дедлайн регистрации прошёл: приём/приглашения закрыты (раздел 7, 409). */
public class RegistrationClosedException extends RuntimeException {

    public RegistrationClosedException(String message) {
        super(message);
    }
}
