package com.plantarena.feed.adapter.out.persistence;

import com.plantarena.feed.domain.FeedCard;
import com.plantarena.feed.domain.FeedCardQuery;
import com.plantarena.feed.domain.FeedCardRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
@RequiredArgsConstructor
public class JdbcFeedCardRepository implements FeedCardRepository {

    private static final UUID SENTINEL = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private static final String FIRST_PAGE = """
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
        """;

    private static final String NEXT_PAGE = """
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
        """;

    private final JdbcClient jdbcClient;

    @Override
    @Transactional
    public void saveAll(List<FeedCard> cards) {
        for (FeedCard card : cards) {
            jdbcClient.sql("""
                    INSERT INTO feed.feed_card (id, window_id, tournament_id, scope, cluster_id,
                        entry_id, user_id, plant_id, asset_id, title, owner_display_name,
                        joined_at, closes_at, created_at)
                    VALUES (:id, :windowId, :tournamentId, :scope, :clusterId, :entryId,
                        :userId, :plantId, :assetId, :title, :ownerDisplayName,
                        :joinedAt, :closesAt, :createdAt)
                    """)
                .param("id", card.id())
                .param("windowId", card.windowId())
                .param("tournamentId", card.tournamentId())
                .param("scope", card.scope())
                .param("clusterId", card.clusterId())
                .param("entryId", card.entryId())
                .param("userId", card.userId())
                .param("plantId", card.plantId())
                .param("assetId", card.assetId())
                .param("title", card.title())
                .param("ownerDisplayName", card.ownerDisplayName())
                .param("joinedAt", Timestamp.from(card.joinedAt()))
                .param("closesAt", Timestamp.from(card.closesAt()))
                .param("createdAt", Timestamp.from(card.createdAt()))
                .update();
        }
    }

    @Override
    @Transactional
    public void deleteAllByWindowId(UUID windowId) {
        jdbcClient.sql("DELETE FROM feed.feed_card WHERE window_id = :windowId")
            .param("windowId", windowId)
            .update();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeedCard> page(FeedCardQuery query) {
        CollectionLike params = paramsOf(query);
        JdbcClient.StatementSpec statement = query.lastSortKey() == null
            ? jdbcClient.sql(FIRST_PAGE)
                .param("seed", query.seed())
                .param("cutoff", Timestamp.from(query.snapshotCutoff()))
                .param("now", Timestamp.from(query.now()))
                .param("scopes", params.scopes)
                .param("participated", params.participated)
                .param("excludedOwner", params.excludedOwner)
                .param("excludedEntries", params.excludedEntries)
                .param("limit", query.limit())
            : jdbcClient.sql(NEXT_PAGE)
                .param("seed", query.seed())
                .param("cutoff", Timestamp.from(query.snapshotCutoff()))
                .param("now", Timestamp.from(query.now()))
                .param("scopes", params.scopes)
                .param("participated", params.participated)
                .param("excludedOwner", params.excludedOwner)
                .param("excludedEntries", params.excludedEntries)
                .param("lastSortKey", query.lastSortKey())
                .param("lastId", query.lastId())
                .param("limit", query.limit());
        return statement.query((rs, rowNum) -> new FeedCard(
            rs.getObject("id", UUID.class),
            rs.getObject("window_id", UUID.class),
            rs.getObject("tournament_id", UUID.class),
            rs.getString("scope"),
            rs.getObject("cluster_id", UUID.class),
            rs.getObject("entry_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("plant_id", UUID.class),
            rs.getObject("asset_id", UUID.class),
            rs.getString("title"),
            rs.getString("owner_display_name"),
            rs.getTimestamp("joined_at").toInstant(),
            rs.getTimestamp("closes_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getLong("sort_key"))).list();
    }

    private CollectionLike paramsOf(FeedCardQuery query) {
        Set<UUID> participated = query.participatedTournamentIds().isEmpty()
            ? Set.of(SENTINEL) : query.participatedTournamentIds();
        Set<UUID> excludedEntries = query.excludedEntryIds().isEmpty()
            ? Set.of(SENTINEL) : query.excludedEntryIds();
        UUID excludedOwner = query.excludedOwnerUserId() != null
            ? query.excludedOwnerUserId() : SENTINEL;
        return new CollectionLike(query.scopes(), participated, excludedOwner, excludedEntries);
    }

    private record CollectionLike(Set<String> scopes, Set<UUID> participated,
                                   UUID excludedOwner, Set<UUID> excludedEntries) {
    }
}
