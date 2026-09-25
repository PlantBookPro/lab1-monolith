package com.plantarena.plants.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат plants: запрет изображения для пары (ownerId, fingerprint)
 * (раздел 6, допущения 2–3). Append-only: история гибели не удаляется;
 * активность вычисляет ImageReusePolicy, а не статус. PERMANENT без expiresAt;
 * COOLDOWN блокирует, пока now &lt; expiresAt.
 */
public final class ImageRestriction {

    private final UUID id;
    private final UUID ownerId;
    private final ImageFingerprint fingerprint;
    private final RestrictionKind kind;
    private final Instant expiresAt;
    private final String reason;
    private final UUID sourceEntryId;
    private final Instant createdAt;

    private ImageRestriction(UUID id, UUID ownerId, ImageFingerprint fingerprint, RestrictionKind kind,
                             Instant expiresAt, String reason, UUID sourceEntryId, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.expiresAt = expiresAt;
        this.reason = requireReason(reason);
        this.sourceEntryId = sourceEntryId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (kind == RestrictionKind.PERMANENT && expiresAt != null) {
            throw new IllegalArgumentException("PERMANENT-запрет не имеет expiresAt");
        }
        if (kind == RestrictionKind.COOLDOWN && expiresAt == null) {
            throw new IllegalArgumentException("COOLDOWN-запрет требует expiresAt");
        }
    }

    /** Запрет навсегда (поражение в закрытом турнире, допущение 2). */
    public static ImageRestriction permanent(UUID ownerId, ImageFingerprint fingerprint,
                                             String reason, UUID sourceEntryId, Instant now) {
        return new ImageRestriction(UUID.randomUUID(), ownerId, fingerprint,
            RestrictionKind.PERMANENT, null, reason, sourceEntryId, now);
    }

    /** Временный запрет (поражение в глобальном турнире — 24 ч, допущение 2). */
    public static ImageRestriction cooldown(UUID ownerId, ImageFingerprint fingerprint,
                                            String reason, UUID sourceEntryId,
                                            Instant expiresAt, Instant now) {
        return new ImageRestriction(UUID.randomUUID(), ownerId, fingerprint,
            RestrictionKind.COOLDOWN, Objects.requireNonNull(expiresAt, "expiresAt"),
            reason, sourceEntryId, now);
    }

    /** Восстановление из хранилища (JPA-адаптер). */
    public static ImageRestriction restore(UUID id, UUID ownerId, ImageFingerprint fingerprint,
                                           RestrictionKind kind, Instant expiresAt, String reason,
                                           UUID sourceEntryId, Instant createdAt) {
        return new ImageRestriction(id, ownerId, fingerprint, kind, expiresAt,
            reason, sourceEntryId, createdAt);
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public ImageFingerprint fingerprint() {
        return fingerprint;
    }

    public RestrictionKind kind() {
        return kind;
    }

    /** null для PERMANENT; для COOLDOWN — момент окончания. */
    public Instant expiresAt() {
        return expiresAt;
    }

    public String reason() {
        return reason;
    }

    public UUID sourceEntryId() {
        return sourceEntryId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Причина запрета обязательна (для истории)");
        }
        return reason.trim();
    }
}
