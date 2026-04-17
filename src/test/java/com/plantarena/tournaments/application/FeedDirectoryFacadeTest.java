package com.plantarena.tournaments.application;

import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.application.support.InMemoryVotingWindowRepository;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingSubject;
import com.plantarena.tournaments.domain.VotingWindow;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Фасад read-контракта feed (раздел 9): делегирует портам репозиториев. */
@DisplayName("FeedDirectoryFacade: оцененные entry и турниры участия")
class FeedDirectoryFacadeTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryVotingWindowRepository windows = new InMemoryVotingWindowRepository();
    private final InMemoryTournamentEntryRepository entries =
        new InMemoryTournamentEntryRepository(tournaments);
    private final FeedDirectoryFacade facade =
        new FeedDirectoryFacade(windows, entries);

    @Test
    @DisplayName("оцененные субъектом entry — из открытых окон")
    void оцененные() {
        UUID user = UUID.randomUUID();
        UUID entry1 = UUID.randomUUID();
        UUID entry2 = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        VotingWindow window = VotingWindow.open(tournamentId, 1, List.of(
                new VotingWindow.ParticipantSeed(entry1, UUID.randomUUID(), NOW),
                new VotingWindow.ParticipantSeed(entry2, user, NOW)),
            NOW, NOW.plusSeconds(3600), NOW);
        window.castVote(VotingSubject.user(user), entry1, VoteValue.LIKE, NOW);
        windows.save(window);

        assertThat(facade.findVotedEntryIdsInOpenWindows(
                VotingSubject.user(user).subjectKey()))
            .containsExactly(entry1);
    }

    @Test
    @DisplayName("турниры участия: допущен к старту — включая выбывшего (допущение 9)")
    void турниры_участия() {
        UUID user = UUID.randomUUID();
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        entries.save(TournamentEntry.admit(t1, user, UUID.randomUUID(), UUID.randomUUID(), NOW));
        TournamentEntry eliminated = entries.save(TournamentEntry.admit(t2, user,
            UUID.randomUUID(), UUID.randomUUID(), NOW));
        eliminated.eliminate();
        entries.save(eliminated);

        assertThat(facade.findParticipatedTournamentIds(user)).containsExactlyInAnyOrder(t1, t2);
    }
}
