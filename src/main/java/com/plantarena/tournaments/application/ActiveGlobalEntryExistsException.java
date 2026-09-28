package com.plantarena.tournaments.application;

/** 409 GLOBAL_ENTRY_ACTIVE. */
public final class ActiveGlobalEntryExistsException extends RuntimeException {

    public ActiveGlobalEntryExistsException(String message) {
        super(message);
    }
}
