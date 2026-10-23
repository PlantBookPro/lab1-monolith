package com.plantarena.media.api;


public class AssetInUseException extends RuntimeException {

    public AssetInUseException(String message) {
        super(message);
    }
}
