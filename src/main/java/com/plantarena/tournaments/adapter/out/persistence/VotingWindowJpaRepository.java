package com.plantarena.tournaments.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data репозиторий окна (раздел 12.1: блокировка PESSIMISTIC_WRITE). */
public interface VotingWindowJpaRepository extends JpaRepository<VotingWindowJpaEntity, UUID> {

    /** SELECT ... FOR UPDATE: сериализует голоса и закрытие одного окна. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from VotingWindowJpaEntity w where w.id = :id")
    Optional<VotingWindowJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("select w.id from VotingWindowJpaEntity w "
        + "where w.status = 'OPEN' and w.closesAt <= :now order by w.closesAt")
    List<UUID> findDueForClose(@Param("now") Instant now, Pageable pageable);

    List<VotingWindowJpaEntity> findByTournamentIdOrderBySequence(UUID tournamentId,
                                                                  Pageable pageable);

    long countByTournamentId(UUID tournamentId);

    Optional<VotingWindowJpaEntity> findFirstByTournamentIdOrderBySequenceDesc(UUID tournamentId);

    List<VotingWindowJpaEntity> findAllByTournamentIdOrderBySequence(UUID tournamentId);

    @Query("select w.id from VotingWindowJpaEntity w "
        + "where w.scope = :scope and w.status = 'OPEN' and w.closesAt <= :now "
        + "order by w.closesAt")
    List<UUID> findDueForCloseByScope(@Param("scope") String scope, @Param("now") Instant now,
                                      Pageable pageable);

    Optional<VotingWindowJpaEntity> findFirstByTournamentIdAndScopeAndStatusOrderBySequenceDesc(
        UUID tournamentId, String scope, String status);

    Optional<VotingWindowJpaEntity> findFirstByTournamentIdAndScopeOrderBySequenceDesc(
        UUID tournamentId, String scope);

    List<VotingWindowJpaEntity> findByEpochIdAndStatusOrderByClusterKeyAsc(UUID epochId,
                                                                           String status);

    Optional<VotingWindowJpaEntity> findByClusterIdAndStatus(UUID clusterId, String status);

    long countByEpochIdAndStatus(UUID epochId, String status);
}
