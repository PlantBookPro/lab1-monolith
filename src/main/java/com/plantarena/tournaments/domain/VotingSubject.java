package com.plantarena.tournaments.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Субъект голосования (раздел 9): USER(userId) — итерация 6; GUEST(токен
 * гостевой сессии) — итерация 8 (глобальные окна). subjectKey — стабильный
 * ключ уникальности (окно, entry, субъект).
 */
public final class VotingSubject {

    private final UUID userId;

    private VotingSubject(UUID userId) {
        this.userId = Objects.requireNonNull(userId, "userId");
    }

    public static VotingSubject user(UUID userId) {
        return new VotingSubject(userId);
    }

    /** Ключ уникальности голоса (хранится в БД как VARCHAR). */
    public String subjectKey() {
        return "USER:" + userId;
    }

    /** Самоголосование (допущение 6): субъект — владелец участия? */
    public boolean isUser(UUID candidate) {
        return userId.equals(candidate);
    }

    public UUID userId() {
        return userId;
    }
}
