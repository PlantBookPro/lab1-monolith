package com.plantarena.plants.application;

/** Файл уже задействован другим (неархивированным) растением (ADR-008, 409). */
public final class AssetAlreadyClaimedException extends RuntimeException {

    public AssetAlreadyClaimedException(String message) {
        super(message);
    }
}
