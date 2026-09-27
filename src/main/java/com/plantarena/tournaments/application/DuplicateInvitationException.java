package com.plantarena.tournaments.application;

/** Дубль приглашения: пара (турнир, пользователь) уникальна (раздел 7, 409). */
public class DuplicateInvitationException extends RuntimeException {

    public DuplicateInvitationException(String message) {
        super(message);
    }
}
