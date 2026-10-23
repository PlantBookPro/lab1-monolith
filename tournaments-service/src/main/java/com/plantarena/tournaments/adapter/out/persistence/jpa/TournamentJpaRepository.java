package com.plantarena.tournaments.adapter.out.persistence.jpa;

import com.plantarena.tournaments.domain.TournamentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TournamentJpaRepository
        extends JpaRepository<TournamentEntity, UUID>, JpaSpecificationExecutor<TournamentEntity> {

    /**
     * Видимость search/count — ровно как в монолите (JdbcTournamentRepository,
     * SEARCH_CONDITIONS): только PRIVATE; не-админ видит свои, с активным
     * приглашением (INVITED/ACCEPTED_PENDING_MODERATION/READY) или с участием.
     * Invitation/entry — не JPA-сущности, поэтому native SQL с exists-подзапросами.
     */
    String SEARCH_VISIBILITY = """
            t.type = 'PRIVATE'
            and (:admin = true or t.creator_id = :userId
                or exists (select 1 from tournaments.invitation i
                    where i.tournament_id = t.id and i.user_id = :userId
                        and i.status in ('INVITED', 'ACCEPTED_PENDING_MODERATION', 'READY'))
                or exists (select 1 from tournaments.tournament_entry e
                    where e.tournament_id = t.id and e.user_id = :userId))
            and (cast(:status as varchar) is null or t.status = cast(:status as varchar))
            and (cast(:tagId as uuid) is null or exists (select 1 from tournaments.tournament_tag tt
                where tt.tournament_id = t.id and tt.tag_id = cast(:tagId as uuid)))
            """;

    @Query(value = "select t.* from tournaments.tournament t where " + SEARCH_VISIBILITY
            + " order by t.created_at desc, t.id asc",
        countQuery = "select count(*) from tournaments.tournament t where " + SEARCH_VISIBILITY,
        nativeQuery = true)
    Page<TournamentEntity> search(@Param("admin") boolean admin, @Param("userId") UUID userId,
                                  @Param("status") String status, @Param("tagId") UUID tagId,
                                  Pageable pageable);

    @Query(value = "select count(*) from tournaments.tournament t where " + SEARCH_VISIBILITY,
        nativeQuery = true)
    long countForSearch(@Param("admin") boolean admin, @Param("userId") UUID userId,
                        @Param("status") String status, @Param("tagId") UUID tagId);

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
