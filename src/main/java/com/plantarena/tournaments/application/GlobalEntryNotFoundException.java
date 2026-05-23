package com.plantarena.tournaments.application;

/** 404 GLOBAL_ENTRY_NOT_FOUND. */
public final class GlobalEntryNotFoundException extends RuntimeException {

    public GlobalEntryNotFoundException(String message) {
        super(message);
    }
}
