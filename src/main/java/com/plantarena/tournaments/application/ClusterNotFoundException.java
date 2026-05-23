package com.plantarena.tournaments.application;

/** 404 CLUSTER_NOT_FOUND. */
public final class ClusterNotFoundException extends RuntimeException {

    public ClusterNotFoundException(String message) {
        super(message);
    }
}
