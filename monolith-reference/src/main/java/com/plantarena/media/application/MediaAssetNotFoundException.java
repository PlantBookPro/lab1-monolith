package com.plantarena.media.application;


public class MediaAssetNotFoundException extends RuntimeException {

    public MediaAssetNotFoundException(String message) {
        super(message);
    }
}
