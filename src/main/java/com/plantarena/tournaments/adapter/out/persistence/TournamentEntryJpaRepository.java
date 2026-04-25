package com.plantarena.tournaments.adapter.out.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data для tournament_entry. */
public interface TournamentEntryJpaRepository extends JpaRepository<TournamentEntryJpaEntity,
        UUID> {

    List<TournamentEntryJpaEntity> findByTournamentIdOrderByJoinedAtAscIdAsc(UUID tournamentId,
                                                                             Pageable pageable);

    long countByTournamentId(UUID tournamentId);

    boolean existsByTournamentIdAndUserId(UUID tournamentId, UUID userId);
}
