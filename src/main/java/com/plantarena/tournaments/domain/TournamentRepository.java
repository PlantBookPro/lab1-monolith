package com.plantarena.tournaments.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


public interface TournamentRepository {

    Tournament save(Tournament tournament);

    Optional<Tournament> findById(UUID id);

    
    void delete(UUID id);

    List<Tournament> search(TournamentFilter filter);

    long count(TournamentFilter filter);

    
    List<Tournament> findDueForStart(Instant now, int limit);

    
    record TournamentFilter(UUID userId, boolean admin, TournamentStatus status, UUID tagId,
                            int offset, int size) {
    }
}
