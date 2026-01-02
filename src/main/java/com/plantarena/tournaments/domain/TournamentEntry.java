package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат tournaments (раздел 7): участие пользователя с растением в
 * закрытом турнире. Создаётся при старте из READY-заявки; после старта
 * состав неизменяем. Уникальность (tournamentId, userId) — БД.
 */
public final class TournamentEntry {

    private final UUID id;
    private final UUID tournamentId;
    private final UUID userId;
    private final UUID plantId;
    private final UUID reservationId;
    private final EntryStatus status;
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

    /** Допуск READY-заявки к старту (use case старта, ADR-010). */
    public static TournamentEntry admit(UUID tournamentId, UUID userId, UUID plantId,
                                        UUID reservationId, Instant now) {
        return new TournamentEntry(UUID.randomUUID(), tournamentId, userId, plantId,
            reservationId, EntryStatus.ACTIVE, now);
    }

    /** Восстановление из хранилища (использует только persistence-адаптер). */
    public static TournamentEntry restore(UUID id, UUID tournamentId, UUID userId,
                                          UUID plantId, UUID reservationId,
                                          EntryStatus status, Instant joinedAt) {
        return new TournamentEntry(id, tournamentId, userId, plantId, reservationId,
            status, joinedAt);
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
