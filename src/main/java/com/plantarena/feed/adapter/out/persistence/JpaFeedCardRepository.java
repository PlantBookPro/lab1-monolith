package com.plantarena.feed.adapter.out.persistence;

import com.plantarena.feed.domain.FeedCard;
import com.plantarena.feed.domain.FeedCardQuery;
import com.plantarena.feed.domain.FeedCardRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
public class JpaFeedCardRepository implements FeedCardRepository {

    
    private static final UUID SENTINEL = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final FeedCardJpaRepository jpaRepository;

    public JpaFeedCardRepository(FeedCardJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public void saveAll(List<FeedCard> cards) {
        for (FeedCard card : cards) {
            FeedCardJpaEntity entity = new FeedCardJpaEntity();
            entity.setId(card.id());
            entity.setWindowId(card.windowId());
            entity.setTournamentId(card.tournamentId());
            entity.setScope(card.scope());
            entity.setClusterId(card.clusterId());
            entity.setEntryId(card.entryId());
            entity.setUserId(card.userId());
            entity.setPlantId(card.plantId());
            entity.setAssetId(card.assetId());
            entity.setTitle(card.title());
            entity.setOwnerDisplayName(card.ownerDisplayName());
            entity.setJoinedAt(card.joinedAt());
            entity.setClosesAt(card.closesAt());
            entity.setCreatedAt(card.createdAt());
            jpaRepository.save(entity);
        }
    }

    @Override
    @Transactional
    public void deleteAllByWindowId(UUID windowId) {
        jpaRepository.deleteAllByWindowId(windowId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeedCard> page(FeedCardQuery query) {
        CollectionLike params = paramsOf(query);
        List<Object[]> rows = query.lastSortKey() == null
            ? jpaRepository.firstPage(query.seed(), query.snapshotCutoff(), query.now(),
                params.scopes, params.participated, params.excludedOwner, params.excludedEntries,
                query.limit())
            : jpaRepository.nextPage(query.seed(), query.snapshotCutoff(), query.now(),
                params.scopes, params.participated, params.excludedOwner, params.excludedEntries,
                query.lastSortKey(), query.lastId(), query.limit());
        List<FeedCard> cards = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            cards.add(new FeedCard((UUID) row[0], (UUID) row[1], (UUID) row[2], (String) row[3],
                (UUID) row[4], (UUID) row[5], (UUID) row[6], (UUID) row[7], (UUID) row[8],
                (String) row[9], (String) row[10], (Instant) row[11], (Instant) row[12],
                (Instant) row[13], ((Number) row[14]).longValue()));
        }
        return cards;
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
