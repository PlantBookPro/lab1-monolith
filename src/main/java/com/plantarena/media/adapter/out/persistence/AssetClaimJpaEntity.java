package com.plantarena.media.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA-модель asset_claim (ADR-008). PK — asset_id: один файл — одно
 * задействованное растение; запись перезаписывается upsert-ом фасада,
 * поэтому без version (optimistic locking не нужен).
 */
@Entity
@Table(name = "asset_claim", schema = "media")
public class AssetClaimJpaEntity {

    @Id
    private UUID assetId;

    @Column(name = "plant_id", nullable = false)
    private UUID plantId;

    @Column(name = "publicly_visible", nullable = false)
    private boolean publiclyVisible;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;

    protected AssetClaimJpaEntity() {
    }

    AssetClaimJpaEntity(UUID assetId, UUID plantId, boolean publiclyVisible, Instant claimedAt) {
        this.assetId = assetId;
        this.plantId = plantId;
        this.publiclyVisible = publiclyVisible;
        this.claimedAt = claimedAt;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public UUID getPlantId() {
        return plantId;
    }

    public boolean isPubliclyVisible() {
        return publiclyVisible;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }
}
