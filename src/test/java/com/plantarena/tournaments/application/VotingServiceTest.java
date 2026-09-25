package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import com.plantarena.tournaments.application.port.out.AbuseSignals;
import com.plantarena.tournaments.application.support.InMemoryGuestSessionRepository;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.application.support.InMemoryVotingWindowRepository;
import com.plantarena.tournaments.domain.GuestSession;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingSubject;
import com.plantarena.tournaments.domain.VotingWindow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Голосование (раздел 9 + допущения 6/9): права, дельты, идемпотентность;
 * субъект GUEST по X-Guest-Token — только глобальные окна, лимит 429.
 */
@DisplayName("Голосование: участник турнира, не сам, окно открыто; гость — глобальные")
class VotingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations = new InMemoryInvitationRepository();
    private final InMemoryTournamentEntryRepository entries = new InMemoryTournamentEntryRepository();
    private final InMemoryVotingWindowRepository windows = new InMemoryVotingWindowRepository();
    private final InMemoryGuestSessionRepository guestSessions = new InMemoryGuestSessionRepository();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final FixedWindowRateLimiter rateLimiter = new FixedWindowRateLimiter(clock);
    private final GuestSessionsSettings guestSettings = new GuestSessionsSettings(
        Duration.ofHours(24), 10, 30);
    private final RecordingAbuseSignals abuseSignals = new RecordingAbuseSignals();
    private final VotingService service = new VotingService(windows, tournaments, invitations,
        entries, guestSessions, new TournamentsAccessPolicy(), rateLimiter, guestSettings,
        abuseSignals, clock);

    private UUID tournamentId;
    private UUID windowId;
    private UUID entry1;
    private UUID entry2;
    private UUID entry3;
    private UUID user1;
    private UUID user2;
    private UUID user3;
    private UUID organizer;
    private UUID globalWindowId;
    private UUID globalEntryId;
    private UUID globalEntryId2;

    @BeforeEach
    void setUp() {
        organizer = UUID.randomUUID();
        user1 = UUID.randomUUID();
        user2 = UUID.randomUUID();
        user3 = UUID.randomUUID();
        Instant deadline = NOW.minusSeconds(60); // дедлайн прошёл — турнир RUNNING
        Tournament tournament = Tournament.createDraft(organizer, "Турнир", null,
            deadline, Duration.ofSeconds(3600), 0.5, 2, Set.of(), deadline.minusSeconds(600));
        tournament.openRegistration(deadline.minusSeconds(599));
        tournament.start(NOW, 3);
        tournaments.save(tournament);
        tournamentId = tournament.id();

        entry1 = entries.save(TournamentEntry.admit(tournamentId, user1, UUID.randomUUID(),
            UUID.randomUUID(), NOW)).id();
        entry2 = entries.save(TournamentEntry.admit(tournamentId, user2, UUID.randomUUID(),
            UUID.randomUUID(), NOW)).id();
        entry3 = entries.save(TournamentEntry.admit(tournamentId, user3, UUID.randomUUID(),
            UUID.randomUUID(), NOW)).id();

        VotingWindow window = VotingWindow.open(tournamentId, 1, seeds(), NOW,
            NOW.plusSeconds(3600), NOW);
        windows.save(window);
        windowId = window.id();

        // глобальное квалификационное окно (раздел 8): entry2 + entry3
        tournaments.save(Tournament.global(GlobalCompetitionId.VALUE, organizer, NOW));
        VotingWindow qualification = VotingWindow.openQualification(GlobalCompetitionId.VALUE,
            UUID.randomUUID(), UUID.randomUUID(), "u4v2", 1,
            java.util.List.of(new VotingWindow.ParticipantSeed(entry2, user2, NOW),
                new VotingWindow.ParticipantSeed(entry3, user3, NOW)),
            NOW.minusSeconds(60), NOW.plusSeconds(3600), NOW.minusSeconds(60));
        windows.save(qualification);
        globalWindowId = qualification.id();
        globalEntryId = entry2;
        globalEntryId2 = entry3;
    }

    @Test
    @DisplayName("участник голосует LIKE → 200-контракт: новый счёт 1")
    void участник_голосует() {
        assertThat(service.cast(actor(user1), null, windowId, entry2, "LIKE")).isEqualTo(1L);
        assertThat(service.myVote(actor(user1), null, windowId, entry2)).contains("LIKE");
    }

    @Test
    @DisplayName("выбывший участник продолжает голосовать до завершения (допущение 9)")
    void выбывший_голосует() {
        entries.save(TournamentEntry.restore(entry1, tournamentId, user1,
            UUID.randomUUID(), UUID.randomUUID(),
            com.plantarena.tournaments.domain.EntryStatus.ELIMINATED, NOW));
        assertThat(service.cast(actor(user1), null, windowId, entry2, "LIKE")).isEqualTo(1L);
    }

    @Test
    @DisplayName("организатор без участия — 403; посторонний — 404 (турнир скрыт)")
    void не_участник_не_голосует() {
        assertThatThrownBy(() -> service.cast(actor(organizer), null, windowId, entry2, "LIKE"))
            .isInstanceOf(com.plantarena.shared.security.AccessDeniedException.class);
        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> service.cast(actor(stranger), null, windowId, entry2, "LIKE"))
            .isInstanceOf(TournamentNotFoundException.class);
    }

    @Test
    @DisplayName("гость без токена — 401 (гостевые сессии — раздел 9)")
    void гость_не_голосует() {
        CurrentActor guest = new CurrentActor(null, Set.of(), true);
        assertThatThrownBy(() -> service.cast(guest, null, windowId, entry2, "LIKE"))
            .isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    @DisplayName("самоголосование запрещено (допущение 6)")
    void самоголосование() {
        assertThatThrownBy(() -> service.cast(actor(user1), null, windowId, entry1, "LIKE"))
            .isInstanceOf(SelfVoteForbiddenException.class);
    }

    @Test
    @DisplayName("entry не из окна — 404; неизвестное окно — 404")
    void entry_и_окно() {
        assertThatThrownBy(() -> service.cast(actor(user1), null, windowId, UUID.randomUUID(),
            "LIKE")).isInstanceOf(EntryNotInWindowException.class);
        assertThatThrownBy(() -> service.cast(actor(user1), null, UUID.randomUUID(), entry2,
            "LIKE")).isInstanceOf(WindowNotFoundException.class);
    }

    @Test
    @DisplayName("окно закрыто или дедлайн истёк — 409 (в closesAt голос уже запрещён)")
    void закрытое_окно() {
        VotingWindow closed = VotingWindow.open(tournamentId, 2, seeds(),
            NOW.minusSeconds(7200), NOW.minusSeconds(3600), NOW.minusSeconds(7200));
        closed.close(NOW.minusSeconds(3600),
            new com.plantarena.tournaments.domain.RoundElimination(), 0.5);
        windows.save(closed);
        assertThatThrownBy(() -> service.cast(actor(user1), null, closed.id(), entry2, "LIKE"))
            .isInstanceOf(VotingClosedException.class);

        VotingWindow expiring = VotingWindow.open(tournamentId, 3, seeds(),
            NOW.minusSeconds(10), NOW, NOW.minusSeconds(10));
        windows.save(expiring);
        assertThatThrownBy(() -> service.cast(actor(user1), null, expiring.id(), entry2, "LIKE"))
            .isInstanceOf(VotingClosedException.class);
    }

    @Test
    @DisplayName("неизвестное значение голоса — 400 (LIKE/DISLIKE)")
    void неизвестное_значение() {
        assertThatThrownBy(() -> service.cast(actor(user1), null, windowId, entry2, "APPLAUSE"))
            .isInstanceOf(UnknownVoteValueException.class);
        assertThatThrownBy(() -> service.cast(actor(user1), null, windowId, entry2, null))
            .isInstanceOf(UnknownVoteValueException.class);
    }

    @Test
    @DisplayName("удаление компенсирует вклад и идемпотентно; myVote — null")
    void удаление() {
        service.cast(actor(user1), null, windowId, entry2, "DISLIKE");
        assertThat(service.remove(actor(user1), null, windowId, entry2)).isZero();
        assertThat(service.remove(actor(user1), null, windowId, entry2)).isZero();
        assertThat(service.myVote(actor(user1), null, windowId, entry2)).isEmpty();
    }

    @Test
    @DisplayName("глобальное окно: посторонний идентифицированный голосует; сам — 403; гость без токена — 401")
    void глобальное_окно_права() {
        UUID stranger = UUID.randomUUID();
        assertThat(service.cast(actor(stranger), null, globalWindowId, globalEntryId, "LIKE"))
            .isEqualTo(1L);
        assertThat(service.myVote(actor(stranger), null, globalWindowId, globalEntryId))
            .contains("LIKE");
        assertThatThrownBy(() -> service.cast(actor(user2), null, globalWindowId, globalEntryId,
            "LIKE")).isInstanceOf(SelfVoteForbiddenException.class);
        CurrentActor guest = new CurrentActor(null, Set.of(), true);
        assertThatThrownBy(() -> service.cast(guest, null, globalWindowId, globalEntryId,
            "LIKE")).isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    @DisplayName("LIKE → DISLIKE даёт −2 (дельта применяется к счёту участника)")
    void смена_знака() {
        assertThat(service.cast(actor(user1), null, windowId, entry2, "LIKE")).isEqualTo(1L);
        assertThat(service.cast(actor(user1), null, windowId, entry2, "DISLIKE")).isEqualTo(-1L);
    }

    @Test
    @DisplayName("гость с активной сессией голосует в глобальном окне: субъект GUEST:<sessionId>")
    void гость_голосует_в_глобальном() {
        UUID sessionId = UUID.randomUUID();
        String token = GuestTokens.newToken();
        guestSessions.save(GuestSession.issue(sessionId, GuestTokens.sha256Hex(token),
            NOW, Duration.ofHours(1)));
        CurrentActor guest = CurrentActor.guest();

        long score = service.cast(guest, token, globalWindowId, globalEntryId, "LIKE");

        assertThat(score).isEqualTo(1L);
        assertThat(windows.findById(globalWindowId).orElseThrow()
                .myVote("GUEST:" + sessionId, globalEntryId))
            .isEqualTo(VoteValue.LIKE);
    }

    @Test
    @DisplayName("гость не голосует в закрытом окне: 404 (турнир скрыт, раздел 13)")
    void гость_в_закрытом_404() {
        String token = GuestTokens.newToken();
        guestSessions.save(GuestSession.issue(UUID.randomUUID(), GuestTokens.sha256Hex(token),
            NOW, Duration.ofHours(1)));

        assertThatThrownBy(() -> service.cast(CurrentActor.guest(), token, windowId,
                entry2, "LIKE"))
            .isInstanceOf(TournamentNotFoundException.class);
    }

    @Test
    @DisplayName("истёкшая сессия и мусорный токен — 401")
    void истёкшая_сессия() {
        String expired = GuestTokens.newToken();
        guestSessions.save(GuestSession.issue(UUID.randomUUID(),
            GuestTokens.sha256Hex(expired), NOW.minus(Duration.ofHours(2)),
            Duration.ofHours(1)));

        assertThatThrownBy(() -> service.cast(CurrentActor.guest(), expired, globalWindowId,
                globalEntryId, "LIKE")).isInstanceOf(NotIdentifiedException.class);
        assertThatThrownBy(() -> service.cast(CurrentActor.guest(), "garbage", globalWindowId,
                globalEntryId, "LIKE")).isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    @DisplayName("идентифицированный пользователь при обоих заголовках — субъект USER (раздел 9)")
    void пользователь_при_гостевом_токене() {
        String token = GuestTokens.newToken();
        guestSessions.save(GuestSession.issue(UUID.randomUUID(), GuestTokens.sha256Hex(token),
            NOW, Duration.ofHours(1)));

        service.cast(actor(user1), token, globalWindowId, globalEntryId, "LIKE");

        assertThat(windows.findById(globalWindowId).orElseThrow()
                .myVote("USER:" + user1, globalEntryId))
            .isEqualTo(VoteValue.LIKE);
    }

    @Test
    @DisplayName("лимит голосов гостя на сессию — RateLimitExceededException (429)")
    void лимит_голосов_гостя() {
        UUID sessionId = UUID.randomUUID();
        String token = GuestTokens.newToken();
        guestSessions.save(GuestSession.issue(sessionId, GuestTokens.sha256Hex(token),
            NOW, Duration.ofHours(1)));
        GuestSessionsSettings limit1 = new GuestSessionsSettings(Duration.ofHours(24), 10, 1);
        VotingService limited = new VotingService(windows, tournaments, invitations, entries,
            guestSessions, new TournamentsAccessPolicy(), rateLimiter, limit1,
            abuseSignals, clock);

        limited.cast(CurrentActor.guest(), token, globalWindowId, globalEntryId, "LIKE");
        assertThatThrownBy(() -> limited.cast(CurrentActor.guest(), token, globalWindowId,
                globalEntryId2, "LIKE"))
            .isInstanceOf(RateLimitExceededException.class);
        assertThat(abuseSignals.lastAction).isEqualTo("guest-vote-limit");
    }

    private java.util.List<VotingWindow.ParticipantSeed> seeds() {
        return java.util.List.of(
            new VotingWindow.ParticipantSeed(entry1, user1, NOW),
            new VotingWindow.ParticipantSeed(entry2, user2, NOW),
            new VotingWindow.ParticipantSeed(entry3, user3, NOW));
    }

    private CurrentActor actor(UUID userId) {
        return new CurrentActor(userId, Set.of(AppRole.USER), false);
    }

    private static final class RecordingAbuseSignals implements AbuseSignals {

        private String lastAction;

        @Override
        public void signal(String action, String details) {
            this.lastAction = action;
        }
    }
}
