package com.plantarena.plants.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA-модель image_restriction (раздел 11). Append-only: только создаётся
 * и читается — version (optimistic locking) не нужен.
 */
@Entity
@Table(name = "image_restriction", schema = "plants")
public class ImageRestrictionJpaEntity {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "fingerprint_version", nullable = false)
    private int fingerprintVersion;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "source_entry_id")
    private UUID sourceEntryId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ImageRestrictionJpaEntity() {
    }

    ImageRestrictionJpaEntity(UUID id, UUID ownerId, String fingerprint, int fingerprintVersion,
                              String kind, Instant expiresAt, String reason,
                              UUID sourceEntryId, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.fingerprint = fingerprint;
        this.fingerprintVersion = fingerprintVersion;
        this.kind = kind;
        this.expiresAt = expiresAt;
        this.reason = reason;
        this.sourceEntryId = sourceEntryId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public int getFingerprintVersion() {
        return fingerprintVersion;
    }

    public String getKind() {
        return kind;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getReason() {
        return reason;
    }

    public UUID getSourceEntryId() {
        return sourceEntryId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
