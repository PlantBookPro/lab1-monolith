package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.application.support.InMemoryVotingWindowRepository;
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

/** Голосование (раздел 9 + допущения 6/9): права, дельты, идемпотентность. */
@DisplayName("Голосование: участник турнира, не сам, окно открыто")
class VotingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations = new InMemoryInvitationRepository();
    private final InMemoryTournamentEntryRepository entries = new InMemoryTournamentEntryRepository();
    private final InMemoryVotingWindowRepository windows = new InMemoryVotingWindowRepository();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final VotingService service = new VotingService(windows, tournaments, invitations,
        entries, new TournamentsAccessPolicy(), clock);

    private UUID tournamentId;
    private UUID windowId;
    private UUID entry1;
    private UUID entry2;
    private UUID entry3;
    private UUID user1;
    private UUID user2;
    private UUID user3;
    private UUID organizer;

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
    }

    @Test
    @DisplayName("участник голосует LIKE → 200-контракт: новый счёт 1")
    void участник_голосует() {
        assertThat(service.cast(actor(user1), windowId, entry2, "LIKE")).isEqualTo(1L);
        assertThat(service.myVote(actor(user1), windowId, entry2)).contains("LIKE");
    }

    @Test
    @DisplayName("выбывший участник продолжает голосовать до завершения (допущение 9)")
    void выбывший_голосует() {
        entries.save(TournamentEntry.restore(entry1, tournamentId, user1,
            UUID.randomUUID(), UUID.randomUUID(),
            com.plantarena.tournaments.domain.EntryStatus.ELIMINATED, NOW));
        assertThat(service.cast(actor(user1), windowId, entry2, "LIKE")).isEqualTo(1L);
    }

    @Test
    @DisplayName("организатор без участия — 403; посторонний — 404 (турнир скрыт)")
    void не_участник_не_голосует() {
        assertThatThrownBy(() -> service.cast(actor(organizer), windowId, entry2, "LIKE"))
            .isInstanceOf(com.plantarena.shared.security.AccessDeniedException.class);
        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> service.cast(actor(stranger), windowId, entry2, "LIKE"))
            .isInstanceOf(TournamentNotFoundException.class);
    }

    @Test
    @DisplayName("гость — 401 (гостевые сессии — итерация 8)")
    void гость_не_голосует() {
        CurrentActor guest = new CurrentActor(null, Set.of(), true);
        assertThatThrownBy(() -> service.cast(guest, windowId, entry2, "LIKE"))
            .isInstanceOf(com.plantarena.shared.security.NotIdentifiedException.class);
    }

    @Test
    @DisplayName("самоголосование запрещено (допущение 6)")
    void самоголосование() {
        assertThatThrownBy(() -> service.cast(actor(user1), windowId, entry1, "LIKE"))
            .isInstanceOf(SelfVoteForbiddenException.class);
    }

    @Test
    @DisplayName("entry не из окна — 404; неизвестное окно — 404")
    void entry_и_окно() {
        assertThatThrownBy(() -> service.cast(actor(user1), windowId, UUID.randomUUID(), "LIKE"))
            .isInstanceOf(EntryNotInWindowException.class);
        assertThatThrownBy(() -> service.cast(actor(user1), UUID.randomUUID(), entry2, "LIKE"))
            .isInstanceOf(WindowNotFoundException.class);
    }

    @Test
    @DisplayName("окно закрыто или дедлайн истёк — 409 (в closesAt голос уже запрещён)")
    void закрытое_окно() {
        VotingWindow closed = VotingWindow.open(tournamentId, 2, seeds(),
            NOW.minusSeconds(7200), NOW.minusSeconds(3600), NOW.minusSeconds(7200));
        closed.close(NOW.minusSeconds(3600),
            new com.plantarena.tournaments.domain.RoundElimination(), 0.5);
        windows.save(closed);
        assertThatThrownBy(() -> service.cast(actor(user1), closed.id(), entry2, "LIKE"))
            .isInstanceOf(VotingClosedException.class);

        VotingWindow expiring = VotingWindow.open(tournamentId, 3, seeds(),
            NOW.minusSeconds(10), NOW, NOW.minusSeconds(10));
        windows.save(expiring);
        assertThatThrownBy(() -> service.cast(actor(user1), expiring.id(), entry2, "LIKE"))
            .isInstanceOf(VotingClosedException.class);
    }

    @Test
    @DisplayName("неизвестное значение голоса — 400 (LIKE/DISLIKE)")
    void неизвестное_значение() {
        assertThatThrownBy(() -> service.cast(actor(user1), windowId, entry2, "APPLAUSE"))
            .isInstanceOf(UnknownVoteValueException.class);
        assertThatThrownBy(() -> service.cast(actor(user1), windowId, entry2, null))
            .isInstanceOf(UnknownVoteValueException.class);
    }

    @Test
    @DisplayName("удаление компенсирует вклад и идемпотентно; myVote — null")
    void удаление() {
        service.cast(actor(user1), windowId, entry2, "DISLIKE");
        assertThat(service.remove(actor(user1), windowId, entry2)).isZero();
        assertThat(service.remove(actor(user1), windowId, entry2)).isZero();
        assertThat(service.myVote(actor(user1), windowId, entry2)).isEmpty();
    }

    @Test
    @DisplayName("LIKE → DISLIKE даёт −2 (дельта применяется к счёту участника)")
    void смена_знака() {
        assertThat(service.cast(actor(user1), windowId, entry2, "LIKE")).isEqualTo(1L);
        assertThat(service.cast(actor(user1), windowId, entry2, "DISLIKE")).isEqualTo(-1L);
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
}
