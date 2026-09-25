package com.plantarena.media.api;

/**
 * Файл задействован растением (ADR-008): удаление файла — HTTP 409
 * ASSET_IN_USE; claim чужим растением — конфликт. Часть опубликованного
 * контракта: plants.adapter переводит в AssetAlreadyClaimedException.
 */
public class AssetInUseException extends RuntimeException {

    public AssetInUseException(String message) {
        super(message);
    }
}
