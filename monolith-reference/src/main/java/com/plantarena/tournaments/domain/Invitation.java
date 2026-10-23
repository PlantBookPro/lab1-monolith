package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;


public final class Invitation {

    private final UUID id;
    private final UUID tournamentId;
    private final UUID userId;
    private final UUID invitedBy;
    private InvitationStatus status;
    private final Instant invitedAt;
    private Instant respondedAt;
    private UUID submittedPlantId;
    private UUID reservationId;
    private UUID submissionKey;
    private long version;

    private Invitation(UUID id, UUID tournamentId, UUID userId, UUID invitedBy,
                       InvitationStatus status, Instant invitedAt, Instant respondedAt,
                       UUID submittedPlantId, UUID reservationId, UUID submissionKey,
                       long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tournamentId = Objects.requireNonNull(tournamentId, "tournamentId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.invitedBy = Objects.requireNonNull(invitedBy, "invitedBy");
        this.status = Objects.requireNonNull(status, "status");
        this.invitedAt = Objects.requireNonNull(invitedAt, "invitedAt");
        this.submittedPlantId = submittedPlantId;
        this.reservationId = reservationId;
        this.submissionKey = submissionKey;
        this.respondedAt = respondedAt;
        this.version = version;
    }

    
    public static Invitation invite(UUID tournamentId, UUID userId, UUID invitedBy, Instant now) {
        return new Invitation(UUID.randomUUID(), tournamentId, userId, invitedBy,
            InvitationStatus.INVITED, now, null, null, null, null, 0);
    }

    
    public static Invitation restore(UUID id, UUID tournamentId, UUID userId, UUID invitedBy,
                                     InvitationStatus status, Instant invitedAt,
                                     Instant respondedAt, UUID submittedPlantId,
                                     UUID reservationId, UUID submissionKey, long version) {
        return new Invitation(id, tournamentId, userId, invitedBy, status, invitedAt,
            respondedAt, submittedPlantId, reservationId, submissionKey, version);
    }

    
    public void accept(UUID plantId, UUID reservationId, UUID submissionKey,
                       boolean plantApproved, Instant now) {
        requireStatus(InvitationStatus.INVITED);
        this.submittedPlantId = Objects.requireNonNull(plantId, "plantId");
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
        this.submissionKey = Objects.requireNonNull(submissionKey, "submissionKey");
        this.status = plantApproved
            ? InvitationStatus.READY
            : InvitationStatus.ACCEPTED_PENDING_MODERATION;
        this.respondedAt = now;
    }

    
    public void markReady(Instant now) {
        requireStatus(InvitationStatus.ACCEPTED_PENDING_MODERATION);
        status = InvitationStatus.READY;
        respondedAt = now;
    }

    
    public void rollbackToInvited(Instant now) {
        requireStatus(InvitationStatus.ACCEPTED_PENDING_MODERATION);
        status = InvitationStatus.INVITED;
        reservationId = null;
        submissionKey = null;
        respondedAt = now;
    }

    
    public void decline(Instant now) {
        requireStatus(InvitationStatus.INVITED, InvitationStatus.ACCEPTED_PENDING_MODERATION,
            InvitationStatus.READY);
        status = InvitationStatus.DECLINED;
        reservationId = null;
        submissionKey = null;
        respondedAt = now;
    }

    
    public void revoke(Instant now) {
        requireStatus(InvitationStatus.INVITED);
        status = InvitationStatus.REVOKED;
        respondedAt = now;
    }

    
    public void expire(Instant now) {
        requireStatus(InvitationStatus.INVITED, InvitationStatus.ACCEPTED_PENDING_MODERATION);
        status = InvitationStatus.EXPIRED;
        reservationId = null;
        submissionKey = null;
        respondedAt = now;
    }

    private void requireStatus(InvitationStatus... allowed) {
        for (InvitationStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("Недопустимый статус для операции: " + status);
    }

    public UUID id() {
        return id;
    }

    public UUID tournamentId() {
        return tournamentId;
    }

    public UUID userId() {
        return userId;
    }

    public UUID invitedBy() {
        return invitedBy;
    }

    public InvitationStatus status() {
        return status;
    }

    public Instant invitedAt() {
        return invitedAt;
    }

    public Instant respondedAt() {
        return respondedAt;
    }

    public UUID submittedPlantId() {
        return submittedPlantId;
    }

    public UUID reservationId() {
        return reservationId;
    }

    public UUID submissionKey() {
        return submissionKey;
    }

    public long version() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Invitation other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
