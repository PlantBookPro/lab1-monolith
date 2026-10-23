package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;


public final class QualificationEpoch {

    private final UUID id;
    private final UUID tournamentId;
    private final int sequence;
    private EpochStatus status;
    private final Instant opensAt;
    private final Instant closesAt;
    private final Instant createdAt;
    private long version;

    private QualificationEpoch(UUID id, UUID tournamentId, int sequence, EpochStatus status,
                               Instant opensAt, Instant closesAt, Instant createdAt,
                               long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tournamentId = Objects.requireNonNull(tournamentId, "tournamentId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence >= 1");
        }
        this.sequence = sequence;
        this.status = Objects.requireNonNull(status, "status");
        this.opensAt = Objects.requireNonNull(opensAt, "opensAt");
        this.closesAt = Objects.requireNonNull(closesAt, "closesAt");
        if (!opensAt.isBefore(closesAt)) {
            throw new IllegalArgumentException("opensAt < closesAt (полуоткрытый интервал)");
        }
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.version = version;
    }

    
    public static QualificationEpoch open(UUID id, UUID tournamentId, int sequence,
                                          Instant opensAt, Instant closesAt, Instant now) {
        return new QualificationEpoch(id, tournamentId, sequence, EpochStatus.OPEN,
            opensAt, closesAt, now, 0L);
    }

    
    public static QualificationEpoch restore(UUID id, UUID tournamentId, int sequence,
                                             EpochStatus status, Instant opensAt,
                                             Instant closesAt, Instant createdAt,
                                             long version) {
        return new QualificationEpoch(id, tournamentId, sequence, status, opensAt,
            closesAt, createdAt, version);
    }

    
    public void close(Instant now) {
        if (status != EpochStatus.OPEN) {
            throw new IllegalStateException("Эпоха уже закрыта: " + id);
        }
        if (now.isBefore(closesAt)) {
            throw new IllegalStateException("Эпоха открыта до " + closesAt);
        }
        status = EpochStatus.CLOSED;
    }

    public UUID id() {
        return id;
    }

    public UUID tournamentId() {
        return tournamentId;
    }

    public int sequence() {
        return sequence;
    }

    public EpochStatus status() {
        return status;
    }

    public Instant opensAt() {
        return opensAt;
    }

    public Instant closesAt() {
        return closesAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public long version() {
        return version;
    }
}
