package com.plantarena.feed.adapter.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


interface FeedCardJpaRepository extends JpaRepository<FeedCardJpaEntity, UUID> {

    @Query(value = """
        SELECT id, window_id, tournament_id, scope, cluster_id, entry_id, user_id, plant_id,
               asset_id, title, owner_display_name, joined_at, closes_at, created_at,
               hashtextextended(id::text, :seed) AS sort_key
        FROM feed.feed_card
        WHERE created_at <= :cutoff
          AND closes_at > :now
          AND scope IN (:scopes)
          AND (scope <> 'PRIVATE' OR tournament_id IN (:participated))
          AND user_id <> :excludedOwner
          AND entry_id NOT IN (:excludedEntries)
        ORDER BY sort_key DESC, id DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> firstPage(@Param("seed") long seed,
                             @Param("cutoff") Instant cutoff,
                             @Param("now") Instant now,
                             @Param("scopes") Collection<String> scopes,
                             @Param("participated") Collection<UUID> participated,
                             @Param("excludedOwner") UUID excludedOwner,
                             @Param("excludedEntries") Collection<UUID> excludedEntries,
                             @Param("limit") int limit);

    @Query(value = """
        SELECT id, window_id, tournament_id, scope, cluster_id, entry_id, user_id, plant_id,
               asset_id, title, owner_display_name, joined_at, closes_at, created_at,
               hashtextextended(id::text, :seed) AS sort_key
        FROM feed.feed_card
        WHERE created_at <= :cutoff
          AND closes_at > :now
          AND scope IN (:scopes)
          AND (scope <> 'PRIVATE' OR tournament_id IN (:participated))
          AND user_id <> :excludedOwner
          AND entry_id NOT IN (:excludedEntries)
          AND (hashtextextended(id::text, :seed) < :lastSortKey
               OR (hashtextextended(id::text, :seed) = :lastSortKey AND id < :lastId))
        ORDER BY sort_key DESC, id DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> nextPage(@Param("seed") long seed,
                            @Param("cutoff") Instant cutoff,
                            @Param("now") Instant now,
                            @Param("scopes") Collection<String> scopes,
                            @Param("participated") Collection<UUID> participated,
                            @Param("excludedOwner") UUID excludedOwner,
                            @Param("excludedEntries") Collection<UUID> excludedEntries,
                            @Param("lastSortKey") long lastSortKey,
                            @Param("lastId") UUID lastId,
                            @Param("limit") int limit);

    @Modifying
    @Query("delete from FeedCardJpaEntity c where c.windowId = :windowId")
    int deleteAllByWindowId(@Param("windowId") UUID windowId);
}
