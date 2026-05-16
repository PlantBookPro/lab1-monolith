package com.plantarena.moderation.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA-модель moderation_job (раздел 11); маппинг на домен — явный (в
 * JpaModerationJobRepository). Конкурентный захват due-заданий ловит @Version.
 */
@Entity
@Table(name = "moderation_job", schema = "moderation")
public class ModerationJobJpaEntity {

    @Id
    private UUID id;

    @Column(name = "plant_id", nullable = false)
    private UUID plantId;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "model_version", length = 50)
    private String modelVersion;

    private Float confidence;

    @Column(name = "reason_code", length = 20)
    private String reasonCode;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ModerationJobJpaEntity() {
    }

    ModerationJobJpaEntity(UUID id, UUID plantId, UUID assetId, String status, int attempts,
                           Instant nextAttemptAt, Instant createdAt) {
        this.id = id;
        this.plantId = plantId;
        this.assetId = assetId;
        this.status = status;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = createdAt;
    }

    /** Мутации домена; id/plantId/assetId/createdAt неизменяемы. */
    void update(String status, int attempts, Instant nextAttemptAt, String modelVersion,
                Float confidence, String reasonCode, Instant startedAt, Instant completedAt) {
        this.status = status;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.modelVersion = modelVersion;
        this.confidence = confidence;
        this.reasonCode = reasonCode;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlantId() {
        return plantId;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public Float getConfidence() {
        return confidence;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
