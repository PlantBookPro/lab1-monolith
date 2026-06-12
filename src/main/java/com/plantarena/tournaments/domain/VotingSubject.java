package com.plantarena.tournaments.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Субъект голосования (раздел 9): USER(userId) или GUEST(гостевая сессия).
 * subjectKey — стабильный ключ уникальности (окно, entry, субъект) с
 * префиксом типа: «USER:<uuid>» / «GUEST:<sessionId>». Гость не владеет
 * участиями — самоголосование (допущение 6) к нему неприменимо.
 */
public final class VotingSubject {

    private final UUID userId;     // USER
    private final UUID sessionId;  // GUEST

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

    /** Ключ уникальности голоса (хранится в БД как VARCHAR). */
    public String subjectKey() {
        return userId != null ? "USER:" + userId : "GUEST:" + sessionId;
    }

    /** Самоголосование (допущение 6): субъект — владелец участия? Гость — нет. */
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
