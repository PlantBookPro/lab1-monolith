package com.plantarena.media.application;

/**
 * Файл не найден или скрыт политикой приватности (HTTP 404, раздел 13).
 */
public class MediaAssetNotFoundException extends RuntimeException {

    public MediaAssetNotFoundException(String message) {
        super(message);
    }
}
