package com.plantarena.feed;

import com.plantarena.feed.domain.FeedCard;
import com.plantarena.feed.domain.FeedCardQuery;
import com.plantarena.feed.domain.FeedCardRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт репозитория проекции карточек: фейк и JPA-адаптер ведут себя
 * одинаково по свойствам (детерминизм от seed, keyset без пропусков/дублей,
 * фильтры), но не по значениям хэша (hashtextextended PG ≠ PRF фейка).
 */
@Transactional
@DisplayName("Контракт FeedCardRepository: проекция, keyset, фильтры")
public abstract class FeedCardRepositoryContractTest {

    private static final Instant T0 = Instant.parse("2026-09-28T10:00:00Z");
    private static final Instant LATER = T0.plusSeconds(10);
    private static final Set<String> ALL_SCOPES = Set.of("PRIVATE", "QUALIFICATION", "FINAL");
    private static final Set<String> GLOBAL_SCOPES = Set.of("QUALIFICATION", "FINAL");

    protected abstract FeedCardRepository repository();

    @Test
    @DisplayName("порядок детерминирован seed: одинаковый seed — одинаковый порядок всех карточек")
    void детерминизм_порядка() {
        repository().saveAll(List.of(
            card("a", T0), card("b", T0), card("c", T0), card("d", T0), card("e", T0)));

        List<FeedCard> first = repository().page(query(42L, 10));
        List<FeedCard> second = repository().page(query(42L, 10));

        assertThat(first).hasSize(5);
        assertThat(ids(first)).isEqualTo(ids(second));
        assertThat(first).allSatisfy(c -> assertThat(c.sortKey()).isNotEqualTo(0L));
    }

    @Test
    @DisplayName("keyset-продолжение: страницы не пересекаются и не пропускают карточки")
    void keyset_без_пропусков_и_дублей() {
        repository().saveAll(List.of(
            card("a", T0), card("b", T0), card("c", T0), card("d", T0), card("e", T0)));

        List<FeedCard> page1 = repository().page(query(42L, 2));
        FeedCard last1 = page1.get(1);
        List<FeedCard> page2 = repository().page(new FeedCardQuery(42L, T0.plusSeconds(1),
            T0.plusSeconds(1), ALL_SCOPES, Set.of(), null, Set.of(),
            last1.sortKey(), last1.id(), 2));
        FeedCard last2 = page2.get(1);
        List<FeedCard> page3 = repository().page(new FeedCardQuery(42L, T0.plusSeconds(1),
            T0.plusSeconds(1), ALL_SCOPES, Set.of(), null, Set.of(),
            last2.sortKey(), last2.id(), 2));

        Set<UUID> union = new HashSet<>();
        union.addAll(ids(page1));
        union.addAll(ids(page2));
        union.addAll(ids(page3));
        assertThat(union).hasSize(5);
        assertThat(page3).hasSize(1); // 2 + 2 + 1
    }

    @Test
    @DisplayName("фильтры: cutoff, открытое окно, scope, закрытые турниры, владелец, оцененные")
    void фильтры() {
        UUID privateTournament = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID votedEntry = UUID.fromString("00000000-0000-0000-0000-000000000001");
        repository().saveAll(List.of(
            card("old", T0),                                   // видима
            card("new", LATER),                                // после cutoff — скрыта
            closedCard("closed", T0),                          // окно закрыто — скрыта
            privateCard("priv", T0, privateTournament),        // PRIVATE без участия — скрыта
            ownCard("own", T0, owner),                         // своя — скрыта
            votedCard("voted", T0, votedEntry)));              // оцененная — скрыта

        FeedCardQuery query = new FeedCardQuery(42L, T0.plusSeconds(1), T0.plusSeconds(1),
            ALL_SCOPES, Set.of(), owner, Set.of(votedEntry), null, null, 10);
        List<FeedCard> page = repository().page(query);

        assertThat(ids(page)).containsExactly(idOf("old"));
    }

    @Test
    @DisplayName("гость: только глобальные scope, PRIVATE скрыты даже с участием")
    void гость_только_глобальные() {
        UUID privateTournament = UUID.randomUUID();
        repository().saveAll(List.of(
            card("global", T0),
            privateCard("priv", T0, privateTournament)));

        List<FeedCard> page = repository().page(new FeedCardQuery(42L, T0.plusSeconds(1),
            T0.plusSeconds(1), GLOBAL_SCOPES, Set.of(), null, Set.of(), null, null, 10));

        assertThat(ids(page)).containsExactly(idOf("global"));
    }

    // ---------- helpers: стабильные id из суффиксов ----------

    private UUID idOf(String suffix) {
        return UUID.nameUUIDFromBytes(("feed-card-" + suffix).getBytes());
    }

    private FeedCard card(String suffix, Instant createdAt) {
        return base(suffix, createdAt, "QUALIFICATION", UUID.randomUUID(), null, null);
    }

    private FeedCard closedCard(String suffix, Instant createdAt) {
        return base(suffix, createdAt, "QUALIFICATION", UUID.randomUUID(),
            T0.minusSeconds(1), null); // closes_at <= now
    }

    private FeedCard privateCard(String suffix, Instant createdAt, UUID tournamentId) {
        return base(suffix, createdAt, "PRIVATE", tournamentId, null, null);
    }

    private FeedCard ownCard(String suffix, Instant createdAt, UUID owner) {
        return base(suffix, createdAt, "QUALIFICATION", UUID.randomUUID(), null, owner);
    }

    private FeedCard votedCard(String suffix, Instant createdAt, UUID entryId) {
        FeedCard c = base(suffix, createdAt, "QUALIFICATION", UUID.randomUUID(), null, null);
        return new FeedCard(c.id(), c.windowId(), c.tournamentId(), c.scope(), c.clusterId(),
            entryId, c.userId(), c.plantId(), c.assetId(), c.title(), c.ownerDisplayName(),
            c.joinedAt(), c.closesAt(), c.createdAt(), 0L);
    }

    private FeedCard base(String suffix, Instant createdAt, String scope, UUID tournamentId,
                          Instant closesAt, UUID forcedOwner) {
        UUID id = idOf(suffix);
        return new FeedCard(id, UUID.nameUUIDFromBytes(("w-" + suffix).getBytes()), tournamentId,
            scope, null, id, forcedOwner != null ? forcedOwner : UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), "Фикус " + suffix, "Владелец " + suffix,
            T0, closesAt != null ? closesAt : T0.plusSeconds(3600), createdAt, 0L);
    }

    private FeedCardQuery query(long seed, int limit) {
        return new FeedCardQuery(seed, T0.plusSeconds(1), T0.plusSeconds(1), ALL_SCOPES,
            Set.of(), null, Set.of(), null, null, limit);
    }

    private List<UUID> ids(List<FeedCard> cards) {
        return cards.stream().map(FeedCard::id).toList();
    }
}
