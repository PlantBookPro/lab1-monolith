package com.plantarena.tournaments.application;

/** Участие не входит в состав окна (404). */
public final class EntryNotInWindowException extends RuntimeException {
    public EntryNotInWindowException(String message) {
        super(message);
    }
}
