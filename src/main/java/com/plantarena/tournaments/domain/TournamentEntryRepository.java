package com.plantarena.tournaments.domain;

import java.util.List;
import java.util.UUID;

/** Порт репозитория агрегата TournamentEntry (уникальность пары — БД). */
public interface TournamentEntryRepository {

    TournamentEntry save(TournamentEntry entry);

    List<TournamentEntry> findByTournamentId(UUID tournamentId, int offset, int size);

    long countByTournamentId(UUID tournamentId);

    /** Участие пользователя в турнире (право просмотра, раздел 13). */
    boolean existsByTournamentIdAndUserId(UUID tournamentId, UUID userId);
}
