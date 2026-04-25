package com.plantarena.tournaments.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data для tournament: доступность (раздел 13) и due-дедлайны (раздел 7). */
public interface TournamentJpaRepository extends JpaRepository<TournamentJpaEntity, UUID> {

    @Query("""
        select t from TournamentJpaEntity t
        where (:admin = true or t.creatorId = :userId
            or exists (select 1 from InvitationJpaEntity i
                where i.tournament = t and i.userId = :userId
                    and i.status in ('INVITED', 'ACCEPTED_PENDING_MODERATION', 'READY'))
            or exists (select 1 from TournamentEntryJpaEntity e
                where e.tournament = t and e.userId = :userId))
        and (:status is null or t.status = :status)
        and (:tagId is null or exists (select 1 from TournamentJpaEntity t2 join t2.tags g
                where t2.id = t.id and g.id = :tagId))
        order by t.createdAt desc, t.id asc
        """)
    List<TournamentJpaEntity> search(@Param("admin") boolean admin,
                                     @Param("userId") UUID userId,
                                     @Param("status") String status,
                                     @Param("tagId") UUID tagId, Pageable pageable);

    @Query("""
        select count(t) from TournamentJpaEntity t
        where (:admin = true or t.creatorId = :userId
            or exists (select 1 from InvitationJpaEntity i
                where i.tournament = t and i.userId = :userId
                    and i.status in ('INVITED', 'ACCEPTED_PENDING_MODERATION', 'READY'))
            or exists (select 1 from TournamentEntryJpaEntity e
                where e.tournament = t and e.userId = :userId))
        and (:status is null or t.status = :status)
        and (:tagId is null or exists (select 1 from TournamentJpaEntity t2 join t2.tags g
                where t2.id = t.id and g.id = :tagId))
        """)
    long searchCount(@Param("admin") boolean admin, @Param("userId") UUID userId,
                     @Param("status") String status, @Param("tagId") UUID tagId);

    @Query("""
        select t from TournamentJpaEntity t
        where t.status = 'REGISTRATION_OPEN' and t.registrationDeadline <= :now
        order by t.registrationDeadline asc, t.id asc
        """)
    List<TournamentJpaEntity> findDueForStart(@Param("now") Instant now, Pageable pageable);
}
