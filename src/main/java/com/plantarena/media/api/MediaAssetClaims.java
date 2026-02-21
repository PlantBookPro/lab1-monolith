package com.plantarena.media.api;

import java.util.UUID;

/**
 * Команды задействованности файлов (ADR-008): plants — единственный
 * командующий контекст (Customer–Supplier). claim идемпотентен для того же
 * растения (upsert публичности по решению модерации), конфликтует с чужим
 * (AssetInUseException); release чужого растения и повтор release — no-op
 * (повторная доставка команды безопасна).
 */
public interface MediaAssetClaims {

    void claim(UUID assetId, UUID plantId, boolean publiclyVisible);

    void release(UUID assetId, UUID plantId);
}
