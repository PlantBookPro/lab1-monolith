package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;


@Entity
@Table(name = "qualification_epoch", schema = "tournaments")
public class QualificationEpochJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tournament_id", nullable = false)
    private UUID tournamentId;

    @Column(nullable = false)
    private int sequence;

    @Column(nullable = false)
    private String status;

    @Column(name = "opens_at", nullable = false)
    private Instant opensAt;

    @Column(name = "closes_at", nullable = false)
    private Instant closesAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private Long version;

    UUID getId() {
        return id;
    }

    UUID getTournamentId() {
        return tournamentId;
    }

    int getSequence() {
        return sequence;
    }

    String getStatus() {
        return status;
    }

    Instant getOpensAt() {
        return opensAt;
    }

    Instant getClosesAt() {
        return closesAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    long getVersion() {
        return version;
    }

    void setId(UUID id) {
        this.id = id;
    }

    void setTournamentId(UUID tournamentId) {
        this.tournamentId = tournamentId;
    }

    void setSequence(int sequence) {
        this.sequence = sequence;
    }

    void setStatus(String status) {
        this.status = status;
    }

    void setOpensAt(Instant opensAt) {
        this.opensAt = opensAt;
    }

    void setClosesAt(Instant closesAt) {
        this.closesAt = closesAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
