package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;


@Entity
@Table(name = "invitation", schema = "tournaments")
public class InvitationJpaEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tournament_id", nullable = false)
    private TournamentJpaEntity tournament;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "invited_at", nullable = false)
    private Instant invitedAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "submitted_plant_id")
    private UUID submittedPlantId;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "submission_key")
    private UUID submissionKey;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected InvitationJpaEntity() {
    }

    InvitationJpaEntity(UUID id, TournamentJpaEntity tournament, UUID userId, UUID invitedBy,
                        Instant invitedAt) {
        this.id = id;
        this.tournament = tournament;
        this.userId = userId;
        this.invitedBy = invitedBy;
        this.invitedAt = invitedAt;
        this.status = "INVITED";
    }

    
    void update(String status, Instant respondedAt, UUID submittedPlantId,
                UUID reservationId, UUID submissionKey) {
        this.status = status;
        this.respondedAt = respondedAt;
        this.submittedPlantId = submittedPlantId;
        this.reservationId = reservationId;
        this.submissionKey = submissionKey;
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

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public String getStatus() {
        return status;
    }

    public Instant getInvitedAt() {
        return invitedAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public UUID getSubmittedPlantId() {
        return submittedPlantId;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public UUID getSubmissionKey() {
        return submissionKey;
    }

    public Long getVersion() {
        return version;
    }
}
