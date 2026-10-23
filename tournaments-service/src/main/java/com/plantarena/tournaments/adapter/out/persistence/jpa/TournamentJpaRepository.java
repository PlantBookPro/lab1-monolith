package com.plantarena.tournaments.adapter.out.persistence.jpa;

import com.plantarena.tournaments.domain.TournamentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TournamentJpaRepository
        extends JpaRepository<TournamentEntity, UUID>, JpaSpecificationExecutor<TournamentEntity> {

    @Query("""
        select t from TournamentEntity t
        where t.status = :status and t.registrationDeadline <= :now
        order by t.registrationDeadline asc, t.id asc
        """)
    List<TournamentEntity> findDueForStart(@Param("status") TournamentStatus status,
                                           @Param("now") Instant now, Pageable pageable);

    @Query("select count(t) > 0 from TournamentEntity t where :tagId member of t.tagIds")
    boolean isUsedByTournament(@Param("tagId") UUID tagId);
}
