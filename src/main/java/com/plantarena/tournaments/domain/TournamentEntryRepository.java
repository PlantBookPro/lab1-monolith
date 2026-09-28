package com.plantarena.tournaments.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Порт репозитория агрегата TournamentEntry (уникальность пары — БД). */
public interface TournamentEntryRepository {

    TournamentEntry save(TournamentEntry entry);

    List<TournamentEntry> findByTournamentId(UUID tournamentId, int offset, int size);

    long countByTournamentId(UUID tournamentId);

    /** Участие пользователя в турнире (право просмотра, раздел 13). */
    boolean existsByTournamentIdAndUserId(UUID tournamentId, UUID userId);

    /** Участие по id (закрытие окна, раздел 12.3). */
    Optional<TournamentEntry> findById(UUID id);

    /** Активное глобальное участие пользователя (допущение 5, раздел 11). */
    Optional<TournamentEntry> findActiveGlobalByUserId(UUID tournamentId, UUID userId);

    /** Участия турнира в статусе (глобальная оркестрация, раздел 8). */
    List<TournamentEntry> findByTournamentIdAndStatus(UUID tournamentId, EntryStatus status);
}
