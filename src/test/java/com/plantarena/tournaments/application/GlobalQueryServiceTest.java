package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.web.PaginationParams;
import com.plantarena.tournaments.application.port.in.GetGlobalLeaderboardUseCase.GlobalLeaderboard;
import com.plantarena.tournaments.application.port.in.GetGlobalLeaderboardUseCase.Item;
import com.plantarena.tournaments.application.port.in.ListGlobalClustersUseCase.GlobalCluster;
import com.plantarena.tournaments.application.port.in.ListGlobalClustersUseCase;
import com.plantarena.tournaments.application.port.in.SubmitGlobalEntryUseCase;
import com.plantarena.tournaments.application.support.InMemoryQualificationEpochRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryVotingWindowRepository;
import com.plantarena.tournaments.domain.QualificationEpoch;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingSubject;
import com.plantarena.tournaments.domain.VotingWindow;
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

/** Запросы глобального турнира (раздел 8, алгоритм 9; раздел 13). */
@DisplayName("GlobalQueryService: кластеры, лидерборды, моё участие")
class GlobalQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final InMemoryVotingWindowRepository windows = new InMemoryVotingWindowRepository();
    private final InMemoryQualificationEpochRepository epochs =
        new InMemoryQualificationEpochRepository();
    private final InMemoryTournamentEntryRepository entries = new InMemoryTournamentEntryRepository();
    private final GlobalQueryService service = new GlobalQueryService(windows, epochs, entries,
        new GlobalCompetitionSettings(Duration.ofHours(24), Duration.ofHours(6)),
        new TournamentsAccessPolicy(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("кластеры текущей эпохи: clusterId/ключ/окно/состав, X-Total-Count")
    void кластеры_эпохи() {
        UUID epochId = UUID.randomUUID();
        UUID clusterId = UUID.randomUUID();
        epochs.save(QualificationEpoch.open(epochId, GlobalCompetitionId.VALUE, 1,
            NOW, NOW.plus(Duration.ofHours(24)), NOW));
        windows.save(VotingWindow.openQualification(GlobalCompetitionId.VALUE, epochId,
            clusterId, "u4pu", 1, List.of(seed(), seed()), NOW,
            NOW.plus(Duration.ofHours(24)), NOW));

        ListGlobalClustersUseCase.ClusterListResult result =
            service.list(PaginationParams.of(0, 20));

        assertThat(result.total()).isEqualTo(1);
        GlobalCluster cluster = result.items().get(0);
        assertThat(cluster.clusterId()).isEqualTo(clusterId);
        assertThat(cluster.clusterKey()).isEqualTo("u4pu");
        assertThat(cluster.memberCount()).isEqualTo(2);
        assertThat(cluster.closesAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("финальный лидерборд: scope/windowId/closesAt/asOf + ранжирование")
    void финальный_лидерборд() {
        VotingWindow.ParticipantSeed first = seed();
        VotingWindow.ParticipantSeed second = seed();
        VotingWindow window = VotingWindow.openFinal(GlobalCompetitionId.VALUE, 1,
            List.of(first, second), NOW.minus(Duration.ofHours(1)),
            NOW.plus(Duration.ofHours(5)), NOW);
        window.castVote(VotingSubject.user(UUID.randomUUID()), first.entryId(),
            VoteValue.LIKE, NOW);
        windows.save(window);

        GlobalLeaderboard leaderboard = service.finalLeaderboard(PaginationParams.of(0, 20));

        assertThat(leaderboard.scope()).isEqualTo("FINAL");
        assertThat(leaderboard.windowId()).isEqualTo(window.id());
        assertThat(leaderboard.closesAt()).isEqualTo(window.closesAt());
        assertThat(leaderboard.asOf()).isEqualTo(NOW);
        assertThat(leaderboard.items()).extracting(Item::entryId)
            .containsExactly(first.entryId(), second.entryId());
        assertThat(leaderboard.items().get(0).score()).isEqualTo(1L);
    }

    @Test
    @DisplayName("финала ещё не было — пустой лидерборд без windowId (алгоритм 7: n=0 ждёт)")
    void финала_нет() {
        GlobalLeaderboard leaderboard = service.finalLeaderboard(PaginationParams.of(0, 20));
        assertThat(leaderboard.windowId()).isNull();
        assertThat(leaderboard.items()).isEmpty();
    }

    @Test
    @DisplayName("лидерборд кластера: по clusterId; неизвестный — 404")
    void лидерборд_кластера() {
        UUID clusterId = UUID.randomUUID();
        windows.save(VotingWindow.openQualification(GlobalCompetitionId.VALUE,
            UUID.randomUUID(), clusterId, "u4pu", 1, List.of(seed()), NOW,
            NOW.plus(Duration.ofHours(24)), NOW));

        GlobalLeaderboard leaderboard = service.clusterLeaderboard(clusterId,
            PaginationParams.of(0, 20));
        assertThat(leaderboard.scope()).isEqualTo("QUALIFICATION");
        assertThat(leaderboard.items()).hasSize(1);

        assertThatThrownBy(() -> service.clusterLeaderboard(UUID.randomUUID(),
            PaginationParams.of(0, 20)))
            .isInstanceOf(ClusterNotFoundException.class);
    }

    @Test
    @DisplayName("моё активное участие: QUEUED виден, WITHDRAWN нет")
    void моё_участие() {
        UUID userId = UUID.randomUUID();
        TournamentEntry active = TournamentEntry.queueForGlobal(GlobalCompetitionId.VALUE,
            userId, UUID.randomUUID(), UUID.randomUUID(), NOW);
        TournamentEntry withdrawn = TournamentEntry.queueForGlobal(GlobalCompetitionId.VALUE,
            userId, UUID.randomUUID(), UUID.randomUUID(), NOW);
        withdrawn.withdraw();
        entries.save(active);
        entries.save(withdrawn);

        assertThat(service.findActive(actor(userId)))
            .map(SubmitGlobalEntryUseCase.GlobalEntryView::id).contains(active.id());
    }

    private VotingWindow.ParticipantSeed seed() {
        TournamentEntry entry = TournamentEntry.queueForGlobal(GlobalCompetitionId.VALUE,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW);
        entries.save(entry);
        return new VotingWindow.ParticipantSeed(entry.id(), entry.userId(), entry.joinedAt());
    }

    private CurrentActor actor(UUID userId) {
        return new CurrentActor(userId, Set.of(AppRole.USER), false);
    }
}
