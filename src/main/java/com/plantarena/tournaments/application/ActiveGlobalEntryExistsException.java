package com.plantarena.tournaments.application;


public final class ActiveGlobalEntryExistsException extends RuntimeException {

    public ActiveGlobalEntryExistsException(String message) {
        super(message);
    }
}
