package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.GetLeaderboardUseCase;
import com.plantarena.tournaments.application.port.in.ListResultsUseCase;
import com.plantarena.tournaments.application.port.in.ListRoundsUseCase;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.application.support.InMemoryVotingWindowRepository;
import com.plantarena.tournaments.domain.ParticipantResult;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingSubject;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.WindowParticipant;
import com.plantarena.tournaments.domain.WindowStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Запросы итерации 6: раунды, лидерборд окна, итоги (раздел 13). */
@DisplayName("Раунды, лидерборд и итоги турнира")
class VotingQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations = new InMemoryInvitationRepository();
    private final InMemoryTournamentEntryRepository entries =
        new InMemoryTournamentEntryRepository();
    private final InMemoryVotingWindowRepository windows = new InMemoryVotingWindowRepository();
    private final VotingQueryService service = new VotingQueryService(tournaments, invitations,
        entries, windows, new TournamentsAccessPolicy());

    private UUID tournamentId;
    private UUID entry1;
    private UUID entry2;
    private UUID user1;
    private UUID user2;
    private UUID window1Id;
    private UUID window2Id;

    @BeforeEach
    void setUp() {
        UUID organizer = UUID.randomUUID();
        user1 = UUID.randomUUID();
        user2 = UUID.randomUUID();
        Instant deadline = NOW.minusSeconds(3600);
        Tournament tournament = Tournament.createDraft(organizer, "Турнир", null,
            deadline, Duration.ofSeconds(60), 0.5, 2, Set.of(), deadline.minusSeconds(600));
        tournament.openRegistration(deadline.minusSeconds(599));
        tournament.start(NOW, 2);
        tournaments.save(tournament);
        tournamentId = tournament.id();
        entry1 = entries.save(TournamentEntry.admit(tournamentId, user1, UUID.randomUUID(),
            UUID.randomUUID(), NOW)).id();
        entry2 = entries.save(TournamentEntry.admit(tournamentId, user2, UUID.randomUUID(),
            UUID.randomUUID(), NOW)).id();

        VotingWindow first = VotingWindow.open(tournamentId, 1, seeds(), NOW.minusSeconds(120),
            NOW.minusSeconds(60), NOW.minusSeconds(120));
        first.castVote(VotingSubject.user(UUID.randomUUID()), entry2, VoteValue.LIKE,
            NOW.minusSeconds(100));
        first.close(NOW.minusSeconds(60),
            new com.plantarena.tournaments.domain.RoundElimination(), 0.5);
        windows.save(first);
        window1Id = first.id();

        // второе окно с одним участником: состояние «финальное окно» эмулируется
        // напрямую (restore), т.к. open требует минимум двух участников
        VotingWindow second = VotingWindow.restore(UUID.randomUUID(), tournamentId, 2,
            WindowStatus.OPEN, NOW.minusSeconds(60), NOW.plusSeconds(60), NOW.minusSeconds(60),
            0L,
            List.of(WindowParticipant.restore(UUID.randomUUID(), entry2, user2, 0L,
                ParticipantResult.ACTIVE, NOW)),
            List.of());
        windows.save(second);
        window2Id = second.id();
    }

    @Test
    @DisplayName("раунды: список по sequence с total")
    void раунды() {
        var result = service.listRounds(actor(user1), tournamentId, 0, 20);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).sequence()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo("CLOSED");
        assertThat(result.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("лидерборд без windowId — последнее окно; порядок score DESC с позициями")
    void лидерборд_последнего_окна() {
        GetLeaderboardUseCase.LeaderboardResult result =
            service.get(actor(user1), tournamentId, null, 0, 20);
        assertThat(result.windowId()).isEqualTo(window2Id);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).position()).isEqualTo(1);
    }

    @Test
    @DisplayName("лидерборд конкретного окна: счёт и итоги закрытого раунда")
    void лидерборд_конкретного_окна() {
        GetLeaderboardUseCase.LeaderboardResult result =
            service.get(actor(user1), tournamentId, window1Id, 0, 20);
        assertThat(result.items()).extracting("entryId")
            .containsExactly(entry2, entry1); // +1 первый, 0 второй
        assertThat(result.items().get(0).score()).isEqualTo(1L);
        assertThat(result.items().get(1).result()).isEqualTo("ELIMINATED");
    }

    @Test
    @DisplayName("чужое окно (другой турнир) — 404")
    void чужое_окно() {
        assertThatThrownBy(() -> service.get(actor(user1), tournamentId, UUID.randomUUID(),
            0, 20)).isInstanceOf(WindowNotFoundException.class);
    }

    @Test
    @DisplayName("итоги: WINNER первым, eliminatedInRound проставлен")
    void итоги() {
        entries.save(TournamentEntry.restore(entry2, tournamentId, user2,
            UUID.randomUUID(), UUID.randomUUID(),
            com.plantarena.tournaments.domain.EntryStatus.WINNER, NOW));
        entries.save(TournamentEntry.restore(entry1, tournamentId, user1,
            UUID.randomUUID(), UUID.randomUUID(),
            com.plantarena.tournaments.domain.EntryStatus.ELIMINATED, NOW));
        ListResultsUseCase.ResultListResult result =
            service.listResults(actor(user1), tournamentId, 0, 20);
        assertThat(result.winnerEntryId()).isEqualTo(entry2);
        assertThat(result.items().get(0).entryId()).isEqualTo(entry2);
        assertThat(result.items().get(0).status()).isEqualTo("WINNER");
        assertThat(result.items().get(1).eliminatedInRound()).isEqualTo(1);
    }

    @Test
    @DisplayName("посторонний не видит раунды — 404 (турнир скрыт)")
    void посторонний() {
        assertThatThrownBy(() -> service.listRounds(actor(UUID.randomUUID()), tournamentId, 0, 20))
            .isInstanceOf(TournamentNotFoundException.class);
    }

    private List<VotingWindow.ParticipantSeed> seeds() {
        return List.of(
            new VotingWindow.ParticipantSeed(entry1, user1, NOW),
            new VotingWindow.ParticipantSeed(entry2, UUID.randomUUID(), NOW));
    }

    private CurrentActor actor(UUID userId) {
        return new CurrentActor(userId, Set.of(AppRole.USER), false);
    }
}
