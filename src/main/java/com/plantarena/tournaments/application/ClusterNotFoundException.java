package com.plantarena.tournaments.application;

public final class ClusterNotFoundException extends RuntimeException {

    public ClusterNotFoundException(String message) {
        super(message);
    }
}
