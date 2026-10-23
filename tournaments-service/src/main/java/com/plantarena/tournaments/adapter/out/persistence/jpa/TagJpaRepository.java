package com.plantarena.tournaments.adapter.out.persistence.jpa;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TagJpaRepository extends JpaRepository<TagEntity, UUID> {

    Optional<TagEntity> findByName(String name);

    @Modifying
    @Query(value = "delete from tournaments.tournament_tag where tag_id = :tagId",
        nativeQuery = true)
    void deleteTournamentLinks(@Param("tagId") UUID tagId);
}
