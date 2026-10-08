package com.plantarena.tournaments.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface TagJpaRepository extends JpaRepository<TagJpaEntity, UUID> {

    Optional<TagJpaEntity> findByName(String name);

    List<TagJpaEntity> findAllByOrderByNameAscIdAsc(Pageable pageable);

    @Query("select count(t) > 0 from TournamentJpaEntity t join t.tags g where g.id = :tagId")
    boolean isUsedByTournament(@Param("tagId") UUID tagId);
}
