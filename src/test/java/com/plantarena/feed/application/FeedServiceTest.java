package com.plantarena.feed.application;

import com.plantarena.feed.application.port.in.GetFeedUseCase;
import com.plantarena.feed.application.support.FakeGuestSessions;
import com.plantarena.feed.application.support.FakeVotingDirectory;
import com.plantarena.feed.application.support.InMemoryFeedCardRepository;
import com.plantarena.feed.domain.FeedCard;
import com.plantarena.feed.domain.FeedCursor;
import com.plantarena.feed.domain.FeedOrdering;
import com.plantarena.shared.security.NotIdentifiedException;
import com.plantarena.shared.web.InvalidPaginationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Лента (раздел 9): субъект и права, keyset-пагинация limit+1, курсор
 * привязан к субъекту, оцененные исключаются.
 */
@DisplayName("FeedService: субъект, права, keyset, курсор")
class FeedServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private final InMemoryFeedCardRepository cards = new InMemoryFeedCardRepository();
    private final FakeVotingDirectory voting = new FakeVotingDirectory();
    private final FakeGuestSessions guests = new FakeGuestSessions();
    private final FeedCursorCodec codec = new FeedCursorCodec("test-secret",
        Duration.ofHours(1));
    private final FeedService service = new FeedService(cards, voting, guests,
        new FeedOrdering(), codec, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID viewer = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @Test
    @DisplayName("пользователь: глобальные + закрытые своих турниров, чужие карточки, без total")
    void лента_пользователя() {
        UUID privateTournament = UUID.randomUUID();
        voting.participatedTournamentIds.add(privateTournament);
        cards.saveAll(List.of(
            card("g1", "QUALIFICATION", UUID.randomUUID(), UUID.randomUUID()),
            card("g2", "FINAL", UUID.randomUUID(), UUID.randomUUID()),
            card("p1", "PRIVATE", privateTournament, UUID.randomUUID()),
            card("p2", "PRIVATE", UUID.randomUUID(), UUID.randomUUID()), // чужой закрытый
            card("own", "QUALIFICATION", UUID.randomUUID(), viewer)));   // своя

        GetFeedUseCase.FeedPage page = service.get(
            com.plantarena.shared.security.CurrentActor.identified(viewer, Set.of()),
            null, 20, null);

        assertThat(page.items()).extracting(GetFeedUseCase.FeedItem::entryId)
            .containsExactlyInAnyOrder(idOf("g1"), idOf("g2"), idOf("p1"));
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.items()).allSatisfy(item -> {
            assertThat(item.imageUrl()).startsWith("/api/v1/files/");
            assertThat(item.ownerId()).isNotNull();
            assertThat(item.ownerDisplayName()).isNotBlank();
        });
    }

    @Test
    @DisplayName("гость: только глобальные; без токена и с неизвестным токеном — 401")
    void лента_гостя() {
        cards.saveAll(List.of(
            card("g1", "QUALIFICATION", UUID.randomUUID(), UUID.randomUUID()),
            card("p1", "PRIVATE", UUID.randomUUID(), UUID.randomUUID())));
        String token = "guest-token";
        UUID sessionId = UUID.randomUUID();
        guests.activeByToken.put(token, sessionId);

        GetFeedUseCase.FeedPage page = service.get(
            com.plantarena.shared.security.CurrentActor.guest(), token, 20, null);

        assertThat(page.items()).extracting(GetFeedUseCase.FeedItem::entryId)
            .containsExactly(idOf("g1"));

        assertThatThrownBy(() -> service.get(
            com.plantarena.shared.security.CurrentActor.guest(), null, 20, null))
            .isInstanceOf(NotIdentifiedException.class);
        assertThatThrownBy(() -> service.get(
            com.plantarena.shared.security.CurrentActor.guest(), "unknown", 20, null))
            .isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    @DisplayName("оцененные субъектом карточки исключаются (раздел 9)")
    void оцененные_исключаются() {
        cards.saveAll(List.of(
            card("g1", "QUALIFICATION", UUID.randomUUID(), UUID.randomUUID()),
            card("g2", "QUALIFICATION", UUID.randomUUID(), UUID.randomUUID())));
        voting.votedEntryIds.add(idOf("g1"));

        GetFeedUseCase.FeedPage page = service.get(
            com.plantarena.shared.security.CurrentActor.identified(viewer, Set.of()),
            null, 20, null);

        assertThat(page.items()).extracting(GetFeedUseCase.FeedItem::entryId)
            .containsExactly(idOf("g2"));
    }

    @Test
    @DisplayName("keyset: limit+1 → hasNext и nextCursor; продолжение не повторяет ключ")
    void keyset_пагинация() {
        cards.saveAll(List.of(
            card("g1", "QUALIFICATION", UUID.randomUUID(), UUID.randomUUID()),
            card("g2", "QUALIFICATION", UUID.randomUUID(), UUID.randomUUID()),
            card("g3", "QUALIFICATION", UUID.randomUUID(), UUID.randomUUID())));

        GetFeedUseCase.FeedPage page1 = service.get(
            com.plantarena.shared.security.CurrentActor.identified(viewer, Set.of()),
            null, 2, null);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.hasNext()).isTrue();
        assertThat(page1.nextCursor()).isNotBlank();

        GetFeedUseCase.FeedPage page2 = service.get(
            com.plantarena.shared.security.CurrentActor.identified(viewer, Set.of()),
            null, 2, page1.nextCursor());
        assertThat(page2.items()).extracting(GetFeedUseCase.FeedItem::entryId)
            .containsExactly(idOf("g3"));
        assertThat(page2.hasNext()).isFalse();
        assertThat(page2.nextCursor()).isNull();
    }

    @Test
    @DisplayName("курсор привязан к субъекту: чужой курсор — FeedCursorInvalidException")
    void чужой_курсор() {
        String strangerCursor = codec.encode(new FeedCursor(1L, NOW, null, null,
            "USER:" + stranger));

        assertThatThrownBy(() -> service.get(
            com.plantarena.shared.security.CurrentActor.identified(viewer, Set.of()),
            null, 20, strangerCursor))
            .isInstanceOf(FeedCursorInvalidException.class);
    }

    @Test
    @DisplayName("limit вне 1–50 — InvalidPaginationException (400)")
    void лимит() {
        assertThatThrownBy(() -> service.get(
            com.plantarena.shared.security.CurrentActor.identified(viewer, Set.of()),
            null, 0, null)).isInstanceOf(InvalidPaginationException.class);
        assertThatThrownBy(() -> service.get(
            com.plantarena.shared.security.CurrentActor.identified(viewer, Set.of()),
            null, 51, null)).isInstanceOf(InvalidPaginationException.class);
    }

    // ---------- helpers ----------

    private UUID idOf(String suffix) {
        return UUID.nameUUIDFromBytes(("feed-card-" + suffix).getBytes());
    }

    private FeedCard card(String suffix, String scope, UUID tournamentId, UUID owner) {
        UUID id = idOf(suffix);
        return new FeedCard(id, UUID.nameUUIDFromBytes(("w-" + suffix).getBytes()), tournamentId,
            scope, null, id, owner, UUID.randomUUID(), UUID.randomUUID(),
            "Фикус " + suffix, "Владелец " + suffix, NOW, NOW.plusSeconds(3600),
            NOW.minusSeconds(1), 0L);
    }
}
