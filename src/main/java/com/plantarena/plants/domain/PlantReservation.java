package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат plants: резерв пары (ownerId, fingerprint) за одной заявкой
 * (раздел 6, допущение 4). Set-инвариант «не более одного активного резерва
 * на пару» домен лишь формулирует — обеспечивает частичный уникальный индекс
 * PostgreSQL (раздел 5). Идемпотентность повтора — по idempotency key
 * (ID команды): повтор с тем же ключом возвращает тот же reservationId.
 */
public final class PlantReservation {

    private final UUID id;
    private final UUID ownerId;
    private final UUID plantId;
    private final ImageFingerprint fingerprint;
    private final UUID idempotencyKey;
    private ReservationStatus status;
    private final Instant createdAt;
    private Instant releasedAt;

    private PlantReservation(UUID id, UUID ownerId, UUID plantId, ImageFingerprint fingerprint,
                             UUID idempotencyKey, ReservationStatus status,
                             Instant createdAt, Instant releasedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.plantId = Objects.requireNonNull(plantId, "plantId");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (status == ReservationStatus.RELEASED && releasedAt == null) {
            throw new IllegalArgumentException("RELEASED требует releasedAt");
        }
        this.releasedAt = releasedAt;
    }

    public static PlantReservation reserve(UUID ownerId, UUID plantId, ImageFingerprint fingerprint,
                                           UUID idempotencyKey, Instant now) {
        return new PlantReservation(UUID.randomUUID(), ownerId, plantId, fingerprint,
            idempotencyKey, ReservationStatus.ACTIVE, now, null);
    }

    /** Восстановление из хранилища (JPA-адаптер). */
    public static PlantReservation restore(UUID id, UUID ownerId, UUID plantId,
                                           ImageFingerprint fingerprint, UUID idempotencyKey,
                                           ReservationStatus status, Instant createdAt,
                                           Instant releasedAt) {
        return new PlantReservation(id, ownerId, plantId, fingerprint, idempotencyKey,
            status, createdAt, releasedAt);
    }

    /** Освобождение (отказ/отмена/завершение); идемпотентно. */
    public boolean release(Instant now) {
        if (status == ReservationStatus.RELEASED) {
            return false;
        }
        this.status = ReservationStatus.RELEASED;
        this.releasedAt = now;
        return true;
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public UUID plantId() {
        return plantId;
    }

    public ImageFingerprint fingerprint() {
        return fingerprint;
    }

    public UUID idempotencyKey() {
        return idempotencyKey;
    }

    public ReservationStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant releasedAt() {
        return releasedAt;
    }
}
