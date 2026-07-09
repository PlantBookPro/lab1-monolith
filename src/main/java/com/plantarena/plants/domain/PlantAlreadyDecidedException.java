package com.plantarena.plants.domain;

/**
 * По заявке уже зафиксировано другое решение: устаревший результат модерации
 * не применяется (раздел 6). Доменное исключение; в api-контракт переводит
 * application (PlantModerationService).
 */
public final class PlantAlreadyDecidedException extends RuntimeException {

    public PlantAlreadyDecidedException(String message) {
        super(message);
    }
}
