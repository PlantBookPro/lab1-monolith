package com.plantarena.tournaments;

import com.plantarena.tournaments.domain.ParticipantResult;
import com.plantarena.tournaments.domain.RoundElimination;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingSubject;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import com.plantarena.tournaments.domain.WindowParticipant;
import com.plantarena.tournaments.domain.WindowScope;
import com.plantarena.tournaments.domain.WindowStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт репозитория VotingWindow: фейк и JPA-адаптер ведут себя одинаково
 * (честность фейка из application-тестов). @Transactional на классе — урок
 * итерации 2. newTournamentId(): JPA-наследник создаёт реальный турнир
 * (FK voting_window → tournament), in-memory — случайный UUID.
 */
@Transactional
@DisplayName("Контракт VotingWindowRepository: roundtrip, due, latest")
public abstract class VotingWindowRepositoryContractTest {

    private static final Instant OPENS = Instant.parse("2026-09-27T10:00:00Z");
    private static final Instant CLOSES = OPENS.plusSeconds(60);

    protected abstract VotingWindowRepository repository();

    /** Идентификатор турнира, на который можно ссылаться (FK в JPA-варианте). */
    protected abstract UUID newTournamentId();

    @Test
    @DisplayName("save + findById: окно с участниками, голосами и счётом восстанавливается")
    void roundtrip_окна() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        UUID entry1 = UUID.randomUUID();
        UUID entry2 = UUID.randomUUID();
        VotingWindow window = VotingWindow.open(newTournamentId(), 1, List.of(
            new VotingWindow.ParticipantSeed(entry1, user1, OPENS),
            new VotingWindow.ParticipantSeed(entry2, user2, OPENS)),
            OPENS, CLOSES, OPENS);
        window.castVote(VotingSubject.user(user1), entry2, VoteValue.LIKE, OPENS);
        window.castVote(VotingSubject.user(user2), entry1, VoteValue.DISLIKE, OPENS);
        repository().save(window);

        VotingWindow restored = repository().findById(window.id()).orElseThrow();

        assertThat(restored.sequence()).isEqualTo(1);
        assertThat(restored.status()).isEqualTo(WindowStatus.OPEN);
        assertThat(restored.participants()).hasSize(2);
        assertThat(restored.scoreOf(entry2)).isEqualTo(1L);
        assertThat(restored.scoreOf(entry1)).isEqualTo(-1L);
        assertThat(restored.myVote(VotingSubject.user(user1).subjectKey(), entry2))
            .isEqualTo(VoteValue.LIKE);
        assertThat(restored.votesOf(entry1)).hasSize(1);
    }

    @Test
    @DisplayName("обновление голоса и удаление сохраняются (смена знака, компенсация)")
    void обновление_голосов() {
        UUID user1 = UUID.randomUUID();
        UUID entry1 = UUID.randomUUID();
        UUID entry2 = UUID.randomUUID();
        VotingWindow window = window(user1, entry1, entry2);
        window.castVote(VotingSubject.user(user1), entry2, VoteValue.LIKE, OPENS);
        repository().save(window);

        VotingWindow loaded = repository().findByIdForUpdate(window.id()).orElseThrow();
        loaded.castVote(VotingSubject.user(user1), entry2, VoteValue.DISLIKE, OPENS);
        repository().save(loaded);
        assertThat(repository().findById(window.id()).orElseThrow().scoreOf(entry2))
            .isEqualTo(-1L);

        VotingWindow again = repository().findByIdForUpdate(window.id()).orElseThrow();
        again.removeVote(VotingSubject.user(user1), entry2, OPENS);
        repository().save(again);
        VotingWindow after = repository().findById(window.id()).orElseThrow();
        assertThat(after.scoreOf(entry2)).isZero();
        assertThat(after.votesOf(entry2)).isEmpty();
    }

    @Test
    @DisplayName("закрытое окно сохраняет результаты участников (история не переписывается)")
    void закрытое_окно() {
        UUID user1 = UUID.randomUUID();
        UUID entry1 = UUID.randomUUID();
        UUID entry2 = UUID.randomUUID();
        VotingWindow window = window(user1, entry1, entry2);
        window.castVote(VotingSubject.user(user1), entry2, VoteValue.LIKE, OPENS);
        window.close(CLOSES, new RoundElimination(), 0.5);
        repository().save(window);

        VotingWindow restored = repository().findById(window.id()).orElseThrow();
        assertThat(restored.status()).isEqualTo(WindowStatus.CLOSED);
        assertThat(restored.scoreOf(entry1)).isEqualTo(0L);
        assertThat(restored.participants()).extracting(WindowParticipant::result)
            .containsExactlyInAnyOrder(ParticipantResult.WINNER, ParticipantResult.ELIMINATED);
    }

    @Test
    @DisplayName("findDueForClose: только OPEN с closesAt <= now, по closesAt")
    void due_окна() {
        UUID tournamentId = newTournamentId();
        UUID user1 = UUID.randomUUID();
        UUID entry1 = UUID.randomUUID();
        UUID entry2 = UUID.randomUUID();
        VotingWindow due = VotingWindow.open(tournamentId, 1, seeds(entry1, entry2, user1),
            OPENS.minusSeconds(120), OPENS.minusSeconds(60), OPENS.minusSeconds(120));
        // другой турнир: частичный уникальный индекс допускает только одно
        // OPEN-окно на турнир (следующее открывается закрытием предыдущего)
        VotingWindow future = VotingWindow.open(newTournamentId(), 2,
            seeds(UUID.randomUUID(), UUID.randomUUID(), user1),
            OPENS, OPENS.plusSeconds(60), OPENS);
        repository().save(due);
        repository().save(future);

        List<UUID> found = repository().findDueForClose(OPENS, 10);

        assertThat(found).containsExactly(due.id());
    }

    @Test
    @DisplayName("findLatestByTournamentId и countByTournamentId")
    void latest_и_count() {
        UUID tournamentId = newTournamentId();
        UUID user1 = UUID.randomUUID();
        VotingWindow first = VotingWindow.open(tournamentId, 1,
            seeds(UUID.randomUUID(), UUID.randomUUID(), user1),
            OPENS.minusSeconds(120), OPENS.minusSeconds(60), OPENS.minusSeconds(120));
        // одно OPEN-окно на турнир: первое закрывается, затем открывается второе
        first.close(OPENS, new RoundElimination(), 0.5);
        repository().save(first);
        VotingWindow second = VotingWindow.open(tournamentId, 2,
            seeds(UUID.randomUUID(), UUID.randomUUID(), user1),
            OPENS.minusSeconds(60), OPENS.plusSeconds(60), OPENS.minusSeconds(60));
        repository().save(second);

        assertThat(repository().findLatestByTournamentId(tournamentId)).contains(second);
        assertThat(repository().countByTournamentId(tournamentId)).isEqualTo(2);
        assertThat(repository().findAllByTournamentId(tournamentId))
            .extracting(VotingWindow::sequence).containsExactly(1, 2);
    }

    @Test
    @DisplayName("scope-запросы: due по scope, открытое/последнее по scope, эпоха/кластер")
    void scope_запросы() {
        VotingWindowRepository repository = repository();
        UUID tournamentId = newTournamentId();
        UUID epochId = UUID.randomUUID();
        UUID clusterId = UUID.randomUUID();
        UUID user1 = UUID.randomUUID();
        Instant past = OPENS.minusSeconds(120);
        VotingWindow qualification = VotingWindow.openQualification(tournamentId, epochId,
            clusterId, "u4pu", 1, seeds(UUID.randomUUID(), UUID.randomUUID(), user1),
            past, past.plusSeconds(60), past);
        VotingWindow finalWindow = VotingWindow.openFinal(tournamentId, 1,
            seeds(UUID.randomUUID(), UUID.randomUUID(), user1), past, past.plusSeconds(60), past);
        repository.save(qualification);
        repository.save(finalWindow);

        assertThat(repository.findDueForCloseByScope(WindowScope.QUALIFICATION, OPENS, 10))
            .containsExactly(qualification.id());
        assertThat(repository.findDueForCloseByScope(WindowScope.FINAL, OPENS, 10))
            .containsExactly(finalWindow.id());
        assertThat(repository.findOpenByScope(tournamentId, WindowScope.FINAL))
            .map(VotingWindow::id).contains(finalWindow.id());
        assertThat(repository.findLatestByTournamentIdAndScope(tournamentId, WindowScope.FINAL))
            .map(VotingWindow::id).contains(finalWindow.id());
        assertThat(repository.findOpenByEpochId(epochId))
            .extracting(VotingWindow::id).containsExactly(qualification.id());
        assertThat(repository.findOpenByClusterId(clusterId))
            .map(VotingWindow::id).contains(qualification.id());
        assertThat(repository.countOpenByEpochId(epochId)).isEqualTo(1);

        qualification.closeQualification(past.plusSeconds(60));
        repository.save(qualification);
        assertThat(repository.countOpenByEpochId(epochId)).isZero();
        assertThat(repository.findOpenByClusterId(clusterId)).isEmpty();
    }

    @Test
    @DisplayName("findVotedEntryIdsInOpenWindows: только голоса субъекта в открытых окнах")
    void голоса_субъекта_в_открытых_окнах() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        UUID entry1 = UUID.randomUUID();
        UUID entry2 = UUID.randomUUID();
        UUID entry3 = UUID.randomUUID();
        UUID tournamentId = newTournamentId();
        VotingWindow open = VotingWindow.open(tournamentId, 1, List.of(
            new VotingWindow.ParticipantSeed(entry1, user1, OPENS),
            new VotingWindow.ParticipantSeed(entry2, user2, OPENS)),
            OPENS, CLOSES, OPENS);
        open.castVote(VotingSubject.user(user1), entry2, VoteValue.LIKE, OPENS);
        repository().save(open);
        VotingWindow closed = VotingWindow.open(tournamentId, 2, List.of(
            new VotingWindow.ParticipantSeed(entry1, user1, OPENS),
            new VotingWindow.ParticipantSeed(entry3, user2, OPENS)),
            OPENS, CLOSES, OPENS);
        closed.castVote(VotingSubject.user(user1), entry3, VoteValue.DISLIKE, OPENS);
        closed.close(CLOSES, new RoundElimination(), 0.5);
        repository().save(closed);

        assertThat(repository().findVotedEntryIdsInOpenWindows(
                VotingSubject.user(user1).subjectKey()))
            .containsExactly(entry2); // голос в закрытом окне не считается
    }

    private VotingWindow window(UUID user1, UUID entry1, UUID entry2) {
        return VotingWindow.open(newTournamentId(), 1,
            seeds(entry1, entry2, user1), OPENS, CLOSES, OPENS);
    }

    private List<VotingWindow.ParticipantSeed> seeds(UUID entry1, UUID entry2, UUID user1) {
        return List.of(
            new VotingWindow.ParticipantSeed(entry1, user1, OPENS),
            new VotingWindow.ParticipantSeed(entry2, UUID.randomUUID(), OPENS));
    }
}
