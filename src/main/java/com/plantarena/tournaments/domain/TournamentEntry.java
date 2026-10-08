package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;


public final class TournamentEntry {

    private final UUID id;
    private final UUID tournamentId;
    private final UUID userId;
    private final UUID plantId;
    private final UUID reservationId;
    private EntryStatus status;
    private final Instant joinedAt;

    private TournamentEntry(UUID id, UUID tournamentId, UUID userId, UUID plantId,
                            UUID reservationId, EntryStatus status, Instant joinedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tournamentId = Objects.requireNonNull(tournamentId, "tournamentId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.plantId = Objects.requireNonNull(plantId, "plantId");
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
        this.status = Objects.requireNonNull(status, "status");
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
    }

    
    public static TournamentEntry admit(UUID tournamentId, UUID userId, UUID plantId,
                                        UUID reservationId, Instant now) {
        return new TournamentEntry(UUID.randomUUID(), tournamentId, userId, plantId,
            reservationId, EntryStatus.ACTIVE, now);
    }

    
    public static TournamentEntry restore(UUID id, UUID tournamentId, UUID userId,
                                          UUID plantId, UUID reservationId,
                                          EntryStatus status, Instant joinedAt) {
        return new TournamentEntry(id, tournamentId, userId, plantId, reservationId,
            status, joinedAt);
    }

    
    public void eliminate() {
        requireActive();
        status = EntryStatus.ELIMINATED;
    }

    
    public void declareWinner() {
        requireActive();
        status = EntryStatus.WINNER;
    }

    
    public static TournamentEntry queueForGlobal(UUID tournamentId, UUID userId, UUID plantId,
                                                 UUID reservationId, Instant now) {
        return new TournamentEntry(UUID.randomUUID(), tournamentId, userId, plantId,
            reservationId, EntryStatus.QUEUED, now);
    }

    
    public void withdraw() {
        requireStatus(EntryStatus.QUEUED);
        status = EntryStatus.WITHDRAWN;
    }

    
    public void startQualifying() {
        requireStatus(EntryStatus.QUEUED);
        status = EntryStatus.QUALIFYING;
    }

    
    public void promoteToFinalPending() {
        requireStatus(EntryStatus.QUALIFYING);
        status = EntryStatus.FINAL_PENDING;
    }

    
    public void becomeFinalist() {
        requireStatus(EntryStatus.FINAL_PENDING);
        status = EntryStatus.FINALIST;
    }

    
    public void eliminateFromGlobal() {
        if (status != EntryStatus.QUALIFYING && status != EntryStatus.FINALIST) {
            throw new IllegalStateException("Итог участия уже зафиксирован: " + status);
        }
        status = EntryStatus.ELIMINATED;
    }

    private void requireStatus(EntryStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                "Ожидается статус " + expected + ", текущий: " + status);
        }
    }

    private void requireActive() {
        if (status != EntryStatus.ACTIVE) {
            throw new IllegalStateException("Итог участия уже зафиксирован: " + status);
        }
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

    public UUID plantId() {
        return plantId;
    }

    public UUID reservationId() {
        return reservationId;
    }

    public EntryStatus status() {
        return status;
    }

    public Instant joinedAt() {
        return joinedAt;
    }
}
