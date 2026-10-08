package com.plantarena.tournaments.adapter.out.persistence;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface VoteJpaRepository extends JpaRepository<VoteJpaEntity, UUID> {

    @Query(value = """
        select wp.entry_id
        from tournaments.vote v
        join tournaments.window_participant wp on wp.id = v.window_participant_id
        join tournaments.voting_window w on w.id = wp.window_id
        where v.subject_key = :subjectKey and w.status = 'OPEN'
        """, nativeQuery = true)
    Set<UUID> findVotedEntryIdsInOpenWindows(@Param("subjectKey") String subjectKey);
}
