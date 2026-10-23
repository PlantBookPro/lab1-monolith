package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;


@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Vote {

    private final @NonNull UUID id;
    private final @NonNull UUID entryId;
    private final @NonNull String subjectKey;
    private @NonNull VoteValue value;
    private final @NonNull Instant createdAt;
    private @NonNull Instant updatedAt;

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
