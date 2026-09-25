package com.plantarena.feed.application.support;

import com.plantarena.feed.domain.FeedCard;
import com.plantarena.feed.domain.FeedCardQuery;
import com.plantarena.feed.domain.FeedCardRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Фейк репозитория карточек для application-тестов. PRF фейка ≠
 * hashtextextended PostgreSQL — контракт фиксирует свойства порядка
 * (детерминизм от seed, keyset без пропусков/дублей), а не значения хэша.
 */
public class InMemoryFeedCardRepository implements FeedCardRepository {

    private final Map<UUID, FeedCard> cards = new ConcurrentHashMap<>();

    /** Детерминированный PRF фейка: (id, seed) → long. */
    static long sortKey(UUID id, long seed) {
        return (id.getMostSignificantBits() ^ id.getLeastSignificantBits())
            * 0x9E3779B97F4A7C15L + seed;
    }

    @Override
    public void saveAll(List<FeedCard> newCards) {
        for (FeedCard card : newCards) {
            cards.put(card.id(), card);
        }
    }

    @Override
    public void deleteAllByWindowId(UUID windowId) {
        cards.values().removeIf(card -> card.windowId().equals(windowId));
    }

    @Override
    public List<FeedCard> page(FeedCardQuery query) {
        Comparator<FeedCard> order = Comparator
            .comparingLong((FeedCard c) -> sortKey(c.id(), query.seed())).reversed()
            .thenComparing(FeedCard::id, Comparator.reverseOrder());
        return cards.values().stream()
            .filter(c -> !c.createdAt().isAfter(query.snapshotCutoff()))
            .filter(c -> c.closesAt().isAfter(query.now()))
            .filter(c -> query.scopes().contains(c.scope()))
            .filter(c -> !"PRIVATE".equals(c.scope())
                || query.participatedTournamentIds().contains(c.tournamentId()))
            .filter(c -> query.excludedOwnerUserId() == null
                || !c.userId().equals(query.excludedOwnerUserId()))
            .filter(c -> !query.excludedEntryIds().contains(c.entryId()))
            .filter(c -> query.lastSortKey() == null
                || sortKey(c.id(), query.seed()) < query.lastSortKey()
                || (sortKey(c.id(), query.seed()) == query.lastSortKey()
                    && c.id().compareTo(query.lastId()) < 0))
            .sorted(order)
            .limit(query.limit())
            .map(c -> new FeedCard(c.id(), c.windowId(), c.tournamentId(), c.scope(),
                c.clusterId(), c.entryId(), c.userId(), c.plantId(), c.assetId(), c.title(),
                c.ownerDisplayName(), c.joinedAt(), c.closesAt(), c.createdAt(),
                sortKey(c.id(), query.seed())))
            .collect(Collectors.toList());
    }
}
