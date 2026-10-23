package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;


@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class TournamentEntry {

    private final @NonNull UUID id;
    private final @NonNull UUID tournamentId;
    private final @NonNull UUID userId;
    private final @NonNull UUID plantId;
    private final @NonNull UUID reservationId;
    private @NonNull EntryStatus status;
    private final @NonNull Instant joinedAt;

    
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
