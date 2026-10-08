package com.plantarena.media.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;


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
