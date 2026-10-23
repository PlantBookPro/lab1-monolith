package com.plantarena.tournaments.domain;

import java.util.Optional;
import java.util.UUID;


public interface QualificationEpochRepository {

    QualificationEpoch save(QualificationEpoch epoch);

    Optional<QualificationEpoch> findById(UUID id);

    
    Optional<QualificationEpoch> findOpenByTournamentId(UUID tournamentId);

    
    Optional<QualificationEpoch> findLastByTournamentId(UUID tournamentId);
}
