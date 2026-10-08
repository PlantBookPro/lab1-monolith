package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;


@Entity
@Table(name = "guest_session", schema = "tournaments")
public class GuestSessionJpaEntity {

    @Id
    private UUID id;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    UUID getId() {
        return id;
    }

    String getTokenHash() {
        return tokenHash;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    void setId(UUID id) {
        this.id = id;
    }

    void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
