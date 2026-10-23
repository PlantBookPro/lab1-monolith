package com.plantarena.plants.application;


public final class AssetAlreadyClaimedException extends RuntimeException {

    public AssetAlreadyClaimedException(String message) {
        super(message);
    }
}
