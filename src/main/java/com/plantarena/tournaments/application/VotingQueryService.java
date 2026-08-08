package com.plantarena.tournaments.application;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.GetLeaderboardUseCase;
import com.plantarena.tournaments.application.port.in.ListResultsUseCase;
import com.plantarena.tournaments.application.port.in.ListRoundsUseCase;
import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.ParticipantRanking;
import com.plantarena.tournaments.domain.ParticipantResult;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import com.plantarena.tournaments.domain.WindowParticipant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Запросы итерации 6 (раздел 13): раунды, лидерборд окна (score DESC,
 * позиции), итоги (WINNER первым, раунд выбывания). Доступ — как к турниру
 * (организатор/админ/активное приглашение/участие), скрытое — 404.
 */
@Service
@Transactional(readOnly = true)
public class VotingQueryService implements ListRoundsUseCase, GetLeaderboardUseCase,
        ListResultsUseCase {

    private static final Set<InvitationStatus> ACTIVE_INVITATION_STATUSES = Set.of(
        InvitationStatus.INVITED, InvitationStatus.ACCEPTED_PENDING_MODERATION,
        InvitationStatus.READY);

    private final TournamentRepository tournaments;
    private final InvitationRepository invitations;
    private final TournamentEntryRepository entries;
    private final VotingWindowRepository windows;
    private final TournamentsAccessPolicy accessPolicy;

    public VotingQueryService(TournamentRepository tournaments,
                              InvitationRepository invitations,
                              TournamentEntryRepository entries,
                              VotingWindowRepository windows,
                              TournamentsAccessPolicy accessPolicy) {
        this.tournaments = tournaments;
        this.invitations = invitations;
        this.entries = entries;
        this.windows = windows;
        this.accessPolicy = accessPolicy;
    }

    @Override
    public RoundListResult listRounds(CurrentActor actor, UUID tournamentId, int page, int size) {
        Tournament tournament = find(tournamentId);
        requireViewer(actor, tournament);
        List<RoundData> items = windows.findByTournamentId(tournamentId, page * size, size)
            .stream()
            .map(window -> new RoundData(window.id(), window.sequence(), window.status().name(),
                window.opensAt(), window.closesAt()))
            .toList();
        return new RoundListResult(items, windows.countByTournamentId(tournamentId));
    }

    @Override
    public LeaderboardResult get(CurrentActor actor, UUID tournamentId, UUID windowId,
                                 int page, int size) {
        Tournament tournament = find(tournamentId);
        requireViewer(actor, tournament);
        VotingWindow window = resolveWindow(tournamentId, windowId);
        List<WindowParticipant> ranked = ParticipantRanking.rank(window.participants());
        List<LeaderboardEntry> items = new ArrayList<>();
        int from = Math.min(page * size, ranked.size());
        int to = Math.min(from + size, ranked.size());
        for (int i = from; i < to; i++) {
            WindowParticipant participant = ranked.get(i);
            items.add(new LeaderboardEntry(i + 1, participant.entryId(), participant.userId(),
                participant.score(), participant.result().name()));
        }
        return new LeaderboardResult(window.id(), window.sequence(), window.status().name(),
            window.closesAt(), items, ranked.size());
    }

    @Override
    public ResultListResult listResults(CurrentActor actor, UUID tournamentId, int page, int size) {
        Tournament tournament = find(tournamentId);
        requireViewer(actor, tournament);
        Map<UUID, Integer> eliminatedRound = new HashMap<>();
        UUID winnerEntryId = null;
        for (VotingWindow window : windows.findAllByTournamentId(tournamentId)) {
            for (WindowParticipant participant : window.participants()) {
                if (participant.result() == ParticipantResult.ELIMINATED) {
                    eliminatedRound.put(participant.entryId(), window.sequence());
                }
                if (participant.result() == ParticipantResult.WINNER) {
                    winnerEntryId = participant.entryId();
                }
            }
        }
        List<TournamentEntry> all = entries.findByTournamentId(tournamentId, 0,
            Integer.MAX_VALUE);
        List<ResultData> items = all.stream()
            .sorted(Comparator
                .comparing((TournamentEntry entry) ->
                    entry.status() == EntryStatus.WINNER ? 0 : 1)
                .thenComparing(entry -> eliminatedRound.getOrDefault(entry.id(),
                    Integer.MAX_VALUE), Comparator.reverseOrder())
                .thenComparing(TournamentEntry::joinedAt)
                .thenComparing(TournamentEntry::id))
            .skip((long) page * size).limit(size)
            .map(entry -> new ResultData(entry.id(), entry.userId(), entry.plantId(),
                entry.status().name(), eliminatedRound.get(entry.id())))
            .toList();
        return new ResultListResult(winnerEntryId, items, all.size());
    }

    private VotingWindow resolveWindow(UUID tournamentId, UUID windowId) {
        VotingWindow window = windowId == null
            ? windows.findLatestByTournamentId(tournamentId)
                .orElseThrow(() -> new WindowNotFoundException(
                    "У турнира ещё нет окон: " + tournamentId))
            : windows.findById(windowId)
                .orElseThrow(() -> new WindowNotFoundException("Окно не найдено: " + windowId));
        if (!window.tournamentId().equals(tournamentId)) {
            throw new WindowNotFoundException("Окно не принадлежит турниру: " + windowId);
        }
        return window;
    }

    private void requireViewer(CurrentActor actor, Tournament tournament) {
        accessPolicy.requireTournamentViewer(actor, tournament,
            visibleBeyondOrganizer(actor, tournament.id()));
    }

    private boolean visibleBeyondOrganizer(CurrentActor actor, UUID tournamentId) {
        boolean invited = invitations.findByTournamentIdAndUserId(tournamentId, actor.userId())
            .map(invitation -> ACTIVE_INVITATION_STATUSES.contains(invitation.status()))
            .orElse(false);
        return invited || entries.existsByTournamentIdAndUserId(tournamentId, actor.userId());
    }

    private Tournament find(UUID tournamentId) {
        return tournaments.findById(tournamentId)
            .orElseThrow(() -> new TournamentNotFoundException(
                "Турнир не найден: " + tournamentId));
    }
}
