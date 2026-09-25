package com.plantarena.plants.application.port.out;

import java.util.UUID;

/**
 * Выходной порт plants: команды задействованности файла в media (ADR-008).
 * media — upstream и не может зависеть от plants, поэтому plants сообщает
 * media о занятости и публичности asset командами через media.api.
 */
public interface MediaAssetClaimsGateway {

    /** Задействовать файл за растением; publiclyVisible — после одобрения. Идемпотентно (upsert). */
    void claim(UUID assetId, UUID plantId, boolean publiclyVisible);

    /** Освободить файл растения (архивация). Идемпотентно. */
    void release(UUID assetId, UUID plantId);
}
