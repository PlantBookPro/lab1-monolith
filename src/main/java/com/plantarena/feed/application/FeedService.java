package com.plantarena.feed.application;

import com.plantarena.feed.application.port.in.GetFeedUseCase;
import com.plantarena.feed.application.port.out.GuestSessions;
import com.plantarena.feed.application.port.out.VotingDirectory;
import com.plantarena.feed.domain.FeedCard;
import com.plantarena.feed.domain.FeedCardQuery;
import com.plantarena.feed.domain.FeedCardRepository;
import com.plantarena.feed.domain.FeedCursor;
import com.plantarena.feed.domain.FeedOrdering;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import com.plantarena.shared.web.InvalidPaginationException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Лента (раздел 9): права и фильтры применяются повторно на каждой странице
 * (cursor не даёт прав), запрос limit+1 определяет hasNext, total не
 * возвращается. Псевдослучайный порядок — seed + стабильный id (SQL,
 * ADR-002); snapshotCutoff прячет новых участников до обновления ленты.
 */
@Service
public class FeedService implements GetFeedUseCase {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final Set<String> USER_SCOPES = Set.of("PRIVATE", "QUALIFICATION", "FINAL");
    private static final Set<String> GUEST_SCOPES = Set.of("QUALIFICATION", "FINAL");

    private final FeedCardRepository cards;
    private final VotingDirectory votingDirectory;
    private final GuestSessions guestSessions;
    private final FeedOrdering ordering;
    private final FeedCursorCodec cursorCodec;
    private final Clock clock;

    public FeedService(FeedCardRepository cards, VotingDirectory votingDirectory,
                       GuestSessions guestSessions, FeedOrdering ordering,
                       FeedCursorCodec cursorCodec, Clock clock) {
        this.cards = cards;
        this.votingDirectory = votingDirectory;
        this.guestSessions = guestSessions;
        this.ordering = ordering;
        this.cursorCodec = cursorCodec;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public FeedPage get(CurrentActor actor, String guestToken, Integer limit, String cursor) {
        int resolvedLimit = resolveLimit(limit);
        Subject subject = resolveSubject(actor, guestToken);
        FeedCursor current = resolveCursor(cursor, subject);
        FeedCardQuery query = new FeedCardQuery(current.seed(), current.snapshotCutoff(),
            clock.instant(), subject.scopes(), subject.participatedTournamentIds(),
            subject.excludedOwnerUserId(),
            votingDirectory.findVotedEntryIdsInOpenWindows(subject.subjectKey()),
            current.lastSortKey(), current.lastId(), resolvedLimit + 1);
        List<FeedCard> page = cards.page(query);
        boolean hasNext = page.size() > resolvedLimit;
        List<FeedCard> visible = hasNext ? page.subList(0, resolvedLimit) : page;
        String nextCursor = null;
        if (hasNext) {
            FeedCard last = visible.get(visible.size() - 1);
            nextCursor = cursorCodec.encode(new FeedCursor(current.seed(),
                current.snapshotCutoff(), last.sortKey(), last.id(), subject.subjectKey()));
        }
        return new FeedPage(visible.stream().map(this::toItem).toList(), nextCursor, hasNext);
    }

    private int resolveLimit(Integer limit) {
        int resolved = limit == null ? DEFAULT_LIMIT : limit;
        if (resolved < 1 || resolved > MAX_LIMIT) {
            throw new InvalidPaginationException(
                "limit вне диапазона: " + resolved + " (допустимо 1–" + MAX_LIMIT + ")");
        }
        return resolved;
    }

    private Subject resolveSubject(CurrentActor actor, String guestToken) {
        if (actor != null && !actor.isGuest()) {
            return new Subject("USER:" + actor.userId(), USER_SCOPES,
                votingDirectory.findParticipatedTournamentIds(actor.userId()),
                actor.userId());
        }
        String token = guestToken == null ? "" : guestToken.trim();
        if (token.isEmpty()) {
            throw new NotIdentifiedException(
                "Лента требует пользователя или гостевой токен (X-Guest-Token)");
        }
        UUID sessionId = guestSessions.activeSessionId(token)
            .orElseThrow(() -> new NotIdentifiedException(
                "Гостевая сессия отсутствует или истекла"));
        return new Subject("GUEST:" + sessionId, GUEST_SCOPES, Set.of(), null);
    }

    private FeedCursor resolveCursor(String cursor, Subject subject) {
        if (cursor == null || cursor.isBlank()) {
            return new FeedCursor(ordering.newSeed(), clock.instant(), null, null,
                subject.subjectKey());
        }
        FeedCursor decoded = cursorCodec.decode(cursor, clock.instant());
        if (!decoded.subjectKey().equals(subject.subjectKey())) {
            throw new FeedCursorInvalidException(
                "Курсор выдан другому субъекту — начните свою ленту");
        }
        return decoded;
    }

    private GetFeedUseCase.FeedItem toItem(FeedCard card) {
        return new GetFeedUseCase.FeedItem(card.windowId(), card.scope(), card.tournamentId(),
            card.entryId(), card.plantId(), card.title(),
            "/api/v1/files/" + card.assetId(), card.userId(), card.ownerDisplayName(),
            card.closesAt());
    }

    private record Subject(String subjectKey, Set<String> scopes,
                           Set<UUID> participatedTournamentIds, UUID excludedOwnerUserId) {
    }
}
