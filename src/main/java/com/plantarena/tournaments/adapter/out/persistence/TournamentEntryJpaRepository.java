package com.plantarena.tournaments.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface TournamentEntryJpaRepository extends JpaRepository<TournamentEntryJpaEntity,
        UUID> {

    List<TournamentEntryJpaEntity> findByTournamentIdOrderByJoinedAtAscIdAsc(UUID tournamentId,
                                                                             Pageable pageable);

    long countByTournamentId(UUID tournamentId);

    boolean existsByTournamentIdAndUserId(UUID tournamentId, UUID userId);

    @Query("select e from TournamentEntryJpaEntity e "
        + "where e.tournament.id = :tournamentId and e.userId = :userId "
        + "and e.status in ('QUEUED', 'QUALIFYING', 'FINAL_PENDING', 'FINALIST')")
    Optional<TournamentEntryJpaEntity> findActiveGlobal(@Param("tournamentId") UUID tournamentId,
                                                         @Param("userId") UUID userId);

    List<TournamentEntryJpaEntity> findByTournamentIdAndStatus(UUID tournamentId, String status);

    @Query("select distinct e.tournament.id from TournamentEntryJpaEntity e "
        + "where e.userId = :userId")
    Set<UUID> findTournamentIdsByUserId(@Param("userId") UUID userId);
}
