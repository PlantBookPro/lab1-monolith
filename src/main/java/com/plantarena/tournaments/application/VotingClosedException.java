package com.plantarena.tournaments.application;

/** Окно закрыто или дедлайн истёк — интервал [opensAt, closesAt) (409). */
public final class VotingClosedException extends RuntimeException {
    public VotingClosedException(String message) {
        super(message);
    }
}
