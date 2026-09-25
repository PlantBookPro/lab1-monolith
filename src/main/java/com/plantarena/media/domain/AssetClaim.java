package com.plantarena.media.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Задействованность файла растением (ADR-008): один файл — одно
 * неархивированное растение; публичность управляется plants по решению
 * модерации. Живёт в media, потому что media — upstream и не может
 * зависеть от plants (иначе цикл, раздел 4.3).
 */
public record AssetClaim(UUID assetId, UUID plantId, boolean publiclyVisible, Instant claimedAt) {

    public AssetClaim {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(plantId, "plantId");
        Objects.requireNonNull(claimedAt, "claimedAt");
    }

    public static AssetClaim claimed(UUID assetId, UUID plantId, boolean publiclyVisible,
                                     Instant at) {
        return new AssetClaim(assetId, plantId, publiclyVisible, at);
    }
}
