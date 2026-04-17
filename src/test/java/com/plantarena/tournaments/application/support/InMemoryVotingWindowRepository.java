package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import com.plantarena.tournaments.domain.WindowParticipant;
import com.plantarena.tournaments.domain.WindowScope;
import com.plantarena.tournaments.domain.WindowStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory фейк VotingWindowRepository (блокировка — no-op, как в монолите). */
public class InMemoryVotingWindowRepository implements VotingWindowRepository {

    public final Map<UUID, VotingWindow> windows = new ConcurrentHashMap<>();

    @Override
    public VotingWindow save(VotingWindow window) {
        windows.put(window.id(), window);
        return window;
    }

    @Override
    public Optional<VotingWindow> findById(UUID id) {
        return Optional.ofNullable(windows.get(id));
    }

    @Override
    public Optional<VotingWindow> findByIdForUpdate(UUID id) {
        return findById(id);
    }

    @Override
    public List<UUID> findDueForClose(Instant now, int limit) {
        return windows.values().stream()
            .filter(window -> window.status() == WindowStatus.OPEN
                && !now.isBefore(window.closesAt()))
            .sorted(Comparator.comparing(VotingWindow::closesAt))
            .limit(limit)
            .map(VotingWindow::id)
            .toList();
    }

    @Override
    public List<VotingWindow> findByTournamentId(UUID tournamentId, int offset, int size) {
        return windows.values().stream()
            .filter(window -> window.tournamentId().equals(tournamentId))
            .sorted(Comparator.comparingInt(VotingWindow::sequence))
            .skip(offset).limit(size).toList();
    }

    @Override
    public long countByTournamentId(UUID tournamentId) {
        return windows.values().stream()
            .filter(window -> window.tournamentId().equals(tournamentId)).count();
    }

    @Override
    public Optional<VotingWindow> findLatestByTournamentId(UUID tournamentId) {
        return windows.values().stream()
            .filter(window -> window.tournamentId().equals(tournamentId))
            .max(Comparator.comparingInt(VotingWindow::sequence));
    }

    @Override
    public List<VotingWindow> findAllByTournamentId(UUID tournamentId) {
        return findByTournamentId(tournamentId, 0, Integer.MAX_VALUE);
    }

    @Override
    public List<UUID> findDueForCloseByScope(WindowScope scope, Instant now, int limit) {
        return windows.values().stream()
            .filter(window -> window.scope() == scope
                && window.status() == WindowStatus.OPEN
                && !now.isBefore(window.closesAt()))
            .sorted(Comparator.comparing(VotingWindow::closesAt))
            .limit(limit)
            .map(VotingWindow::id)
            .toList();
    }

    @Override
    public Optional<VotingWindow> findOpenByScope(UUID tournamentId, WindowScope scope) {
        return windows.values().stream()
            .filter(window -> window.tournamentId().equals(tournamentId)
                && window.scope() == scope && window.status() == WindowStatus.OPEN)
            .max(Comparator.comparingInt(VotingWindow::sequence));
    }

    @Override
    public Optional<VotingWindow> findLatestByTournamentIdAndScope(UUID tournamentId,
                                                                   WindowScope scope) {
        return windows.values().stream()
            .filter(window -> window.tournamentId().equals(tournamentId)
                && window.scope() == scope)
            .max(Comparator.comparingInt(VotingWindow::sequence));
    }

    @Override
    public List<VotingWindow> findOpenByEpochId(UUID epochId) {
        return windows.values().stream()
            .filter(window -> epochId.equals(window.epochId())
                && window.status() == WindowStatus.OPEN)
            .sorted(Comparator.comparing(window -> window.clusterKey() == null
                ? "" : window.clusterKey()))
            .toList();
    }

    @Override
    public Optional<VotingWindow> findOpenByClusterId(UUID clusterId) {
        return windows.values().stream()
            .filter(window -> clusterId.equals(window.clusterId())
                && window.status() == WindowStatus.OPEN)
            .findAny();
    }

    @Override
    public long countOpenByEpochId(UUID epochId) {
        return windows.values().stream()
            .filter(window -> epochId.equals(window.epochId())
                && window.status() == WindowStatus.OPEN)
            .count();
    }

    @Override
    public Set<UUID> findVotedEntryIdsInOpenWindows(String subjectKey) {
        Set<UUID> voted = new HashSet<>();
        for (VotingWindow window : windows.values()) {
            if (window.status() != WindowStatus.OPEN) {
                continue;
            }
            for (WindowParticipant participant : window.participants()) {
                if (window.myVote(subjectKey, participant.entryId()) != null) {
                    voted.add(participant.entryId());
                }
            }
        }
        return voted;
    }
}
