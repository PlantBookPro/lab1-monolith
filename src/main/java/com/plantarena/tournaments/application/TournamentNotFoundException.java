package com.plantarena.tournaments.application;

/** Турнир не найден или скрыт политикой приватности (раздел 13). */
public class TournamentNotFoundException extends RuntimeException {

    public TournamentNotFoundException(String message) {
        super(message);
    }
}
