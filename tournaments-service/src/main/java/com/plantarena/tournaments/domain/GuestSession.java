package com.plantarena.tournaments.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;


public final class GuestSession {

    private final UUID id;
    private final String tokenHash;
    private final Instant createdAt;
    private final Instant expiresAt;

    private GuestSession(UUID id, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        if (this.tokenHash.isBlank()) {
            throw new IllegalArgumentException("tokenHash не может быть пустым");
        }
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!this.createdAt.isBefore(this.expiresAt)) {
            throw new IllegalArgumentException("expiresAt должен быть позже createdAt");
        }
    }

    public static GuestSession issue(UUID id, String tokenHash, Instant now, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl должен быть положительным");
        }
        return new GuestSession(id, tokenHash, now, now.plus(ttl));
    }

    
    public static GuestSession restore(UUID id, String tokenHash, Instant createdAt,
                                       Instant expiresAt) {
        return new GuestSession(id, tokenHash, createdAt, expiresAt);
    }

    
    public boolean isActive(Instant now) {
        return now.isBefore(expiresAt);
    }

    public UUID id() {
        return id;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
