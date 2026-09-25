package com.plantarena.plants.application;

/** Файл не найден или чужой (скрыт приватностью, 404 — как в media). */
public final class AssetNotFoundException extends RuntimeException {

    public AssetNotFoundException(String message) {
        super(message);
    }
}
