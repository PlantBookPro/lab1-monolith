package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** JPA-модель окна голосования (раздел 11); маппинг в домен — явный. */
@Entity
@Table(name = "voting_window", schema = "tournaments")
public class VotingWindowJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tournament_id", nullable = false)
    private UUID tournamentId;

    @Column(name = "sequence", nullable = false)
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

    @OneToMany(mappedBy = "window", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WindowParticipantJpaEntity> participants = new ArrayList<>();

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

    List<WindowParticipantJpaEntity> getParticipants() {
        return participants;
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
