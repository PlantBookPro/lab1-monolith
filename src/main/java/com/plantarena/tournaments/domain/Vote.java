package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;


public final class Vote {

    private final UUID id;
    private final UUID entryId;
    private final String subjectKey;
    private VoteValue value;
    private final Instant createdAt;
    private Instant updatedAt;

    private Vote(UUID id, UUID entryId, String subjectKey, VoteValue value,
                 Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.entryId = Objects.requireNonNull(entryId, "entryId");
        this.subjectKey = Objects.requireNonNull(subjectKey, "subjectKey");
        this.value = Objects.requireNonNull(value, "value");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

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
