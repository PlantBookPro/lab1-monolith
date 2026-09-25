package com.plantarena.plants.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA-модель plant (раздел 11); маппинг на домен — явный (в JpaPlantRepository).
 * Plant мутирует (модерация/жизнь/архив) — optimistic locking через @Version.
 */
@Entity
@Table(name = "plant", schema = "plants")
public class PlantJpaEntity {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "fingerprint_version", nullable = false)
    private int fingerprintVersion;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "moderation_status", nullable = false)
    private String moderationStatus;

    @Column(name = "moderation_reason")
    private String moderationReason;

    @Column(name = "life_status", nullable = false)
    private String lifeStatus;

    @Column(name = "died_at")
    private Instant diedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PlantJpaEntity() {
    }

    PlantJpaEntity(UUID id, UUID ownerId, UUID assetId, String fingerprint,
                   int fingerprintVersion, String title, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.assetId = assetId;
        this.fingerprint = fingerprint;
        this.fingerprintVersion = fingerprintVersion;
        this.title = title;
        this.createdAt = createdAt;
    }

    /** Мутации домена; id/owner/asset/fingerprint/created_at неизменяемы. */
    void update(String title, String moderationStatus, String moderationReason,
                String lifeStatus, Instant diedAt, Instant archivedAt) {
        this.title = title;
        this.moderationStatus = moderationStatus;
        this.moderationReason = moderationReason;
        this.lifeStatus = lifeStatus;
        this.diedAt = diedAt;
        this.archivedAt = archivedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public int getFingerprintVersion() {
        return fingerprintVersion;
    }

    public String getTitle() {
        return title;
    }

    public String getModerationStatus() {
        return moderationStatus;
    }

    public String getModerationReason() {
        return moderationReason;
    }

    public String getLifeStatus() {
        return lifeStatus;
    }

    public Instant getDiedAt() {
        return diedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
