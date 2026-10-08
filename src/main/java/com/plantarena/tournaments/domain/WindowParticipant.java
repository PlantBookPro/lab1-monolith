package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;


public final class WindowParticipant {

    private final UUID id;
    private final UUID entryId;
    private final UUID userId;
    private long score;
    private ParticipantResult result;
    private final Instant joinedAt;

    private WindowParticipant(UUID id, UUID entryId, UUID userId, long score,
                              ParticipantResult result, Instant joinedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.entryId = Objects.requireNonNull(entryId, "entryId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.result = Objects.requireNonNull(result, "result");
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
        this.score = score;
    }

    static WindowParticipant newParticipant(UUID entryId, UUID userId, Instant joinedAt) {
        return new WindowParticipant(UUID.randomUUID(), entryId, userId, 0L,
            ParticipantResult.ACTIVE, joinedAt);
    }

    
    public static WindowParticipant restore(UUID id, UUID entryId, UUID userId, long score,
                                            ParticipantResult result, Instant joinedAt) {
        return new WindowParticipant(id, entryId, userId, score, result, joinedAt);
    }

    void applyDelta(long delta) {
        score += delta;
    }

    void eliminate() {
        requireActive();
        result = ParticipantResult.ELIMINATED;
    }

    void survive() {
        requireActive();
        result = ParticipantResult.SURVIVED;
    }

    void declareWinner() {
        requireActive();
        result = ParticipantResult.WINNER;
    }

    void promote() {
        requireActive();
        result = ParticipantResult.PROMOTED;
    }

    private void requireActive() {
        if (result != ParticipantResult.ACTIVE) {
            throw new IllegalStateException("Итог участника уже зафиксирован: " + result);
        }
    }

    public UUID id() {
        return id;
    }

    public UUID entryId() {
        return entryId;
    }

    public UUID userId() {
        return userId;
    }

    public long score() {
        return score;
    }

    public ParticipantResult result() {
        return result;
    }

    public Instant joinedAt() {
        return joinedAt;
    }
}
