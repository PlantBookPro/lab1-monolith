package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.UUID;


@lombok.RequiredArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class Vote {

    private final @lombok.NonNull UUID id;
    private final @lombok.NonNull UUID entryId;
    private final @lombok.NonNull String subjectKey;
    private @lombok.NonNull VoteValue value;
    private final @lombok.NonNull Instant createdAt;
    private @lombok.NonNull Instant updatedAt;

    static Vote newVote(UUID entryId, String subjectKey, VoteValue value, Instant now) {
        return new Vote(UUID.randomUUID(), entryId, subjectKey, value, now, now);
    }

    
    public static Vote restore(UUID id, UUID entryId, String subjectKey, VoteValue value,
                               Instant createdAt, Instant updatedAt) {
        return new Vote(id, entryId, subjectKey, value, createdAt, updatedAt);
    }

    void update(VoteValue value, Instant now) {
        this.value = value;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID entryId() {
        return entryId;
    }

    public String subjectKey() {
        return subjectKey;
    }

    public VoteValue value() {
        return value;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
