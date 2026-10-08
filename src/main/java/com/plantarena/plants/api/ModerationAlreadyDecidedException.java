package com.plantarena.plants.api;


public final class ModerationAlreadyDecidedException extends RuntimeException {

    public ModerationAlreadyDecidedException(String message) {
        super(message);
    }
}
