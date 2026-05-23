package com.plantarena.tournaments.application;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.web.PaginationParams;
import com.plantarena.tournaments.application.port.in.GetGlobalInfoUseCase;
import com.plantarena.tournaments.application.port.in.GetGlobalLeaderboardUseCase;
import com.plantarena.tournaments.application.port.in.GetMyGlobalEntryUseCase;
import com.plantarena.tournaments.application.port.in.ListGlobalClustersUseCase;
import com.plantarena.tournaments.application.port.in.SubmitGlobalEntryUseCase.GlobalEntryView;
import com.plantarena.tournaments.domain.ParticipantRanking;
import com.plantarena.tournaments.domain.QualificationEpochRepository;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import com.plantarena.tournaments.domain.WindowParticipant;
import com.plantarena.tournaments.domain.WindowScope;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Запросы глобального турнира (раздел 8, алгоритм 9; раздел 13): публичные
 * конфигурация/кластеры/лидерборды (scope + windowId + closesAt + asOf) и
 * своё активное участие. Очки разных окон и кластеров не смешиваются.
 */
@Service
@Transactional(readOnly = true)
public class GlobalQueryService implements GetGlobalInfoUseCase, ListGlobalClustersUseCase,
        GetGlobalLeaderboardUseCase, GetMyGlobalEntryUseCase {

    private final VotingWindowRepository windows;
    private final QualificationEpochRepository epochs;
    private final TournamentEntryRepository entries;
    private final GlobalCompetitionSettings settings;
    private final TournamentsAccessPolicy accessPolicy;
    private final Clock clock;

    public GlobalQueryService(VotingWindowRepository windows,
                              QualificationEpochRepository epochs,
                              TournamentEntryRepository entries,
                              GlobalCompetitionSettings settings,
                              TournamentsAccessPolicy accessPolicy, Clock clock) {
        this.windows = windows;
        this.epochs = epochs;
        this.entries = entries;
        this.settings = settings;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    @Override
    public GlobalInfo globalInfo() {
        EpochInfo epoch = epochs.findOpenByTournamentId(GlobalCompetitionId.VALUE)
            .map(found -> new EpochInfo(found.id(), found.sequence(), found.opensAt(),
                found.closesAt()))
            .orElse(null);
        WindowInfo finalWindow = currentFinal()
            .map(window -> new WindowInfo(window.id(), window.sequence(), window.opensAt(),
                window.closesAt()))
            .orElse(null);
        return new GlobalInfo(settings.epochDuration().toSeconds(),
            settings.finalWindowDuration().toSeconds(), epoch, finalWindow);
    }

    @Override
    public ClusterListResult list(PaginationParams page) {
        List<GlobalCluster> clusters = epochs
            .findOpenByTournamentId(GlobalCompetitionId.VALUE)
            .stream()
            .flatMap(epoch -> windows.findOpenByEpochId(epoch.id()).stream())
            .map(window -> new GlobalCluster(window.clusterId(), window.clusterKey(),
                window.id(), window.participants().size(), window.closesAt()))
            .sorted(Comparator.comparing(GlobalCluster::clusterKey)
                .thenComparing(GlobalCluster::clusterId))
            .toList();
        List<GlobalCluster> pageItems = clusters.stream()
            .skip(page.offset()).limit(page.size()).toList();
        return new ClusterListResult(pageItems, clusters.size());
    }

    @Override
    public GlobalLeaderboard finalLeaderboard(PaginationParams page) {
        Optional<VotingWindow> window = currentFinal();
        if (window.isEmpty()) {
            return new GlobalLeaderboard("FINAL", null, null, clock.instant(), List.of());
        }
        return leaderboard("FINAL", window.orElseThrow(), page);
    }

    @Override
    public GlobalLeaderboard clusterLeaderboard(UUID clusterId, PaginationParams page) {
        VotingWindow window = windows.findOpenByClusterId(clusterId)
            .orElseThrow(() -> new ClusterNotFoundException("Кластер не найден: " + clusterId));
        return leaderboard("QUALIFICATION", window, page);
    }

    @Override
    public Optional<GlobalEntryView> findActive(CurrentActor actor) {
        accessPolicy.requireIdentified(actor);
        return entries.findActiveGlobalByUserId(GlobalCompetitionId.VALUE, actor.userId())
            .map(entry -> new GlobalEntryView(entry.id(), entry.plantId(),
                entry.status().name(), entry.joinedAt()));
    }

    private GlobalLeaderboard leaderboard(String scope, VotingWindow window,
                                           PaginationParams page) {
        List<WindowParticipant> ranked = ParticipantRanking.rank(window.participants());
        List<Item> items = ranked.stream()
            .skip(page.offset()).limit(page.size())
            .map(participant -> new Item(participant.entryId(), participant.userId(),
                participant.score()))
            .toList();
        return new GlobalLeaderboard(scope, window.id(), window.closesAt(), clock.instant(),
            items);
    }

    private Optional<VotingWindow> currentFinal() {
        return windows.findOpenByScope(GlobalCompetitionId.VALUE, WindowScope.FINAL)
            .or(() -> windows.findLatestByTournamentIdAndScope(GlobalCompetitionId.VALUE,
                WindowScope.FINAL));
    }
}
