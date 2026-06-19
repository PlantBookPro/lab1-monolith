package com.plantarena.tournaments.application;

/** Самоголосование запрещено для идентифицированного пользователя (403, допущение 6). */
public final class SelfVoteForbiddenException extends RuntimeException {
    public SelfVoteForbiddenException(String message) {
        super(message);
    }
}
