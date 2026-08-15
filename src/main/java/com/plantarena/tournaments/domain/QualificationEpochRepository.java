package com.plantarena.tournaments.domain;

import java.util.Optional;
import java.util.UUID;

/** Порт репозитория агрегата QualificationEpoch (раздел 8). */
public interface QualificationEpochRepository {

    QualificationEpoch save(QualificationEpoch epoch);

    Optional<QualificationEpoch> findById(UUID id);

    /** Открытая эпоха турнира (не более одной — частичный уникальный индекс). */
    Optional<QualificationEpoch> findOpenByTournamentId(UUID tournamentId);

    /** Последняя эпоха турнира (максимум sequence) — нумерация следующих. */
    Optional<QualificationEpoch> findLastByTournamentId(UUID tournamentId);
}
