package com.plantarena.tournaments.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;


public interface QualificationEpochJpaRepository
        extends JpaRepository<QualificationEpochJpaEntity, UUID> {

    Optional<QualificationEpochJpaEntity> findFirstByTournamentIdAndStatusOrderBySequenceDesc(
        UUID tournamentId, String status);

    Optional<QualificationEpochJpaEntity> findFirstByTournamentIdOrderBySequenceDesc(
        UUID tournamentId);
}
