package com.plantarena.plants.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA-модель plant_reservation (раздел 11). Единственный переход
 * ACTIVE→RELEASED идемпотентен (повтор release пишет то же состояние) —
 * version (optimistic locking) не нужен.
 */
@Entity
@Table(name = "plant_reservation", schema = "plants")
public class PlantReservationJpaEntity {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "plant_id", nullable = false)
    private UUID plantId;

    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "fingerprint_version", nullable = false)
    private int fingerprintVersion;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected PlantReservationJpaEntity() {
    }

    PlantReservationJpaEntity(UUID id, UUID ownerId, UUID plantId, String fingerprint,
                              int fingerprintVersion, UUID idempotencyKey, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.plantId = plantId;
        this.fingerprint = fingerprint;
        this.fingerprintVersion = fingerprintVersion;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }

    /** Единственная мутация: ACTIVE → RELEASED. */
    void update(String status, Instant releasedAt) {
        this.status = status;
        this.releasedAt = releasedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getPlantId() {
        return plantId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public int getFingerprintVersion() {
        return fingerprintVersion;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}
