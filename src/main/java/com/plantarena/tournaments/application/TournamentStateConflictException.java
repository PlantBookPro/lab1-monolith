package com.plantarena.tournaments.application;

/** Операция несовместима со статусом турнира/приглашения (раздел 13: 409). */
public class TournamentStateConflictException extends RuntimeException {

    public TournamentStateConflictException(String message) {
        super(message);
    }
}
