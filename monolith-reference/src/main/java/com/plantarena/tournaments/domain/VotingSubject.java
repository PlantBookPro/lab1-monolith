package com.plantarena.tournaments.domain;

import java.util.Objects;
import java.util.UUID;


public final class VotingSubject {

    private final UUID userId;     
    private final UUID sessionId;  

    private VotingSubject(UUID userId, UUID sessionId) {
        if (userId == null && sessionId == null) {
            throw new IllegalArgumentException("Субъект без идентификатора");
        }
        this.userId = userId;
        this.sessionId = sessionId;
    }

    public static VotingSubject user(UUID userId) {
        return new VotingSubject(Objects.requireNonNull(userId, "userId"), null);
    }

    public static VotingSubject guest(UUID sessionId) {
        return new VotingSubject(null, Objects.requireNonNull(sessionId, "sessionId"));
    }

    
    public String subjectKey() {
        return userId != null ? "USER:" + userId : "GUEST:" + sessionId;
    }

    
    public boolean isUser(UUID candidate) {
        return userId != null && userId.equals(candidate);
    }

    public UUID userId() {
        return userId;
    }

    public UUID sessionId() {
        return sessionId;
    }
}
