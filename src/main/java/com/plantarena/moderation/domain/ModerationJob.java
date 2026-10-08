package com.plantarena.moderation.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;


public final class ModerationJob {

    private static final Duration BACKOFF_CAP = Duration.ofHours(1);

    private final UUID id;
    private final UUID plantId;
    private final UUID assetId;
    private ModerationJobStatus status;
    private int attempts;
    private Instant nextAttemptAt;
    private String modelVersion;
    private Float confidence;
    private ModerationReasonCode reasonCode;
    private Instant startedAt;
    private Instant completedAt;
    private final Instant createdAt;
    private long version;

    private ModerationJob(UUID id, UUID plantId, UUID assetId, ModerationJobStatus status,
                          int attempts, Instant nextAttemptAt, String modelVersion,
                          Float confidence, ModerationReasonCode reasonCode, Instant startedAt,
                          Instant completedAt, Instant createdAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.plantId = Objects.requireNonNull(plantId, "plantId");
        this.assetId = Objects.requireNonNull(assetId, "assetId");
        this.status = Objects.requireNonNull(status, "status");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts не может быть отрицательным");
        }
        this.attempts = attempts;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        this.modelVersion = modelVersion;
        this.confidence = confidence;
        this.reasonCode = reasonCode;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
    }

    
    public static ModerationJob create(UUID plantId, UUID assetId, Instant now) {
        return new ModerationJob(UUID.randomUUID(), plantId, assetId, ModerationJobStatus.NEW,
            0, now, null, null, null, null, null, now, 0);
    }

    
    public static ModerationJob restore(UUID id, UUID plantId, UUID assetId,
                                        ModerationJobStatus status, int attempts,
                                        Instant nextAttemptAt, String modelVersion,
                                        Float confidence, ModerationReasonCode reasonCode,
                                        Instant startedAt, Instant completedAt,
                                        Instant createdAt, long version) {
        return new ModerationJob(id, plantId, assetId, status, attempts, nextAttemptAt,
            modelVersion, confidence, reasonCode, startedAt, completedAt, createdAt, version);
    }

    
    public void claim(Instant now) {
        requireStatus(ModerationJobStatus.NEW, ModerationJobStatus.RETRY);
        status = ModerationJobStatus.IN_PROGRESS;
        attempts++;
        if (startedAt == null) {
            startedAt = now;
        }
    }

    
    public void succeed(String modelVersion, float confidence, ModerationReasonCode reasonCode,
                        Instant now) {
        requireStatus(ModerationJobStatus.IN_PROGRESS);
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion обязательна");
        }
        if (confidence < 0f || confidence > 1f) {
            throw new IllegalArgumentException("confidence должна быть в [0..1]");
        }
        if (reasonCode == null || reasonCode == ModerationReasonCode.STALE) {
            throw new IllegalArgumentException("reasonCode успешной попытки — PLANT_DETECTED/NOT_A_PLANT");
        }
        status = ModerationJobStatus.DONE;
        this.modelVersion = modelVersion;
        this.confidence = confidence;
        this.reasonCode = reasonCode;
        completedAt = now;
    }

    
    public void retry(Instant now) {
        requireStatus(ModerationJobStatus.IN_PROGRESS);
        status = ModerationJobStatus.RETRY;
        nextAttemptAt = now.plus(backoff(attempts));
    }

    
    public void completeStale(Instant now) {
        requireStatus(ModerationJobStatus.IN_PROGRESS);
        status = ModerationJobStatus.DONE;
        reasonCode = ModerationReasonCode.STALE;
        completedAt = now;
    }

    
    static Duration backoff(int attempts) {
        if (attempts >= 13) {
            return BACKOFF_CAP;
        }
        return Duration.ofSeconds(1L << (attempts - 1));
    }

    private void requireStatus(ModerationJobStatus... allowed) {
        for (ModerationJobStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException(
            "Недопустимый переход из статуса " + status + " (ожидается "
                + String.join("/", java.util.Arrays.stream(allowed)
                    .map(Enum::name).toList()) + ")");
    }

    public UUID id() {
        return id;
    }

    public UUID plantId() {
        return plantId;
    }

    public UUID assetId() {
        return assetId;
    }

    public ModerationJobStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public String modelVersion() {
        return modelVersion;
    }

    public Float confidence() {
        return confidence;
    }

    public ModerationReasonCode reasonCode() {
        return reasonCode;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ModerationJob other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
