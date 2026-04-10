package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA-модель tournament_entry (раздел 11): создаётся при старте; статус
 * мутирует с итерации 6 (eliminate/winner при закрытии окон) — без version
 * (писатель один: закрытие окна под FOR UPDATE).
 */
@Entity
@Table(name = "tournament_entry", schema = "tournaments")
public class TournamentEntryJpaEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tournament_id", nullable = false)
    private TournamentJpaEntity tournament;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "plant_id", nullable = false)
    private UUID plantId;

    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;

    @Column(name = "status", nullable = false, length = 15)
    private String status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    protected TournamentEntryJpaEntity() {
    }

    TournamentEntryJpaEntity(UUID id, TournamentJpaEntity tournament, UUID userId,
                             UUID plantId, UUID reservationId, Instant joinedAt) {
        this.id = id;
        this.tournament = tournament;
        this.userId = userId;
        this.plantId = plantId;
        this.reservationId = reservationId;
        this.joinedAt = joinedAt;
        this.status = "ACTIVE";
    }

    public UUID getId() {
        return id;
    }

    public TournamentJpaEntity getTournament() {
        return tournament;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getPlantId() {
        return plantId;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public String getStatus() {
        return status;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    void setStatus(String status) {
        this.status = status;
    }
}
