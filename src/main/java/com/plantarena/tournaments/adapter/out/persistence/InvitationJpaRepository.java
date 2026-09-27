package com.plantarena.tournaments.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data для invitation; статусы — строки (маппинг явный). */
public interface InvitationJpaRepository extends JpaRepository<InvitationJpaEntity, UUID> {

    Optional<InvitationJpaEntity> findByTournamentIdAndUserId(UUID tournamentId, UUID userId);

    Optional<InvitationJpaEntity> findBySubmittedPlantIdAndStatus(UUID submittedPlantId,
                                                                  String status);

    List<InvitationJpaEntity> findByTournamentIdOrderByInvitedAtAscIdAsc(UUID tournamentId,
                                                                         Pageable pageable);

    long countByTournamentId(UUID tournamentId);

    List<InvitationJpaEntity> findByUserIdOrderByInvitedAtDescIdAsc(UUID userId,
                                                                    Pageable pageable);

    long countByUserId(UUID userId);

    List<InvitationJpaEntity> findByTournamentIdAndStatus(UUID tournamentId, String status);

    boolean existsByTournamentId(UUID tournamentId);
}
