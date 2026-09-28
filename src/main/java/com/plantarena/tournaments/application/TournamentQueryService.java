package com.plantarena.tournaments.application;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.EntryData;
import com.plantarena.tournaments.api.TournamentData;
import com.plantarena.tournaments.application.port.in.GetTournamentUseCase;
import com.plantarena.tournaments.application.port.in.ListEntriesUseCase;
import com.plantarena.tournaments.application.port.in.ListTournamentsUseCase;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.TournamentStatus;
import com.plantarena.tournaments.domain.TournamentType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Запросы турниров (раздел 13): доступные списки с фильтрами, просмотр
 * (организатор/админ/активное приглашение/участие), участники.
 */
@Service
@Transactional(readOnly = true)
public class TournamentQueryService implements ListTournamentsUseCase, GetTournamentUseCase,
        ListEntriesUseCase {

    private static final Set<InvitationStatus> ACTIVE_INVITATION_STATUSES = Set.of(
        InvitationStatus.INVITED, InvitationStatus.ACCEPTED_PENDING_MODERATION,
        InvitationStatus.READY);

    private final TournamentRepository tournaments;
    private final InvitationRepository invitations;
    private final TournamentEntryRepository entries;
    private final TournamentsAccessPolicy accessPolicy;

    public TournamentQueryService(TournamentRepository tournaments,
                                  InvitationRepository invitations,
                                  TournamentEntryRepository entries,
                                  TournamentsAccessPolicy accessPolicy) {
        this.tournaments = tournaments;
        this.invitations = invitations;
        this.entries = entries;
        this.accessPolicy = accessPolicy;
    }

    @Override
    public TournamentListResult list(CurrentActor actor, String status, UUID tagId,
                                     int page, int size) {
        accessPolicy.requireIdentified(actor);
        TournamentRepository.TournamentFilter filter =
            new TournamentRepository.TournamentFilter(actor.userId(), actor.hasRole(
                com.plantarena.shared.security.AppRole.ADMIN), parseStatus(status), tagId,
                page * size, size);
        List<TournamentData> items = tournaments.search(filter).stream()
            .map(TournamentAssembler::toData)
            .toList();
        return new TournamentListResult(items, tournaments.count(filter));
    }

    /** Фильтр статуса: строка опубликованного языка → домен; неизвестное — 400. */
    private TournamentStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return TournamentStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new UnknownStatusFilterException(
                "Неизвестный статус турнира: " + status
                    + " (допустимо: DRAFT, REGISTRATION_OPEN, RUNNING, FINISHED, CANCELLED)");
        }
    }

    @Override
    public TournamentData get(CurrentActor actor, UUID tournamentId) {
        Tournament tournament = find(tournamentId);
        if (tournament.type() == TournamentType.GLOBAL) {
            throw new TournamentNotFoundException("Турнир не найден: " + tournamentId);
        }
        accessPolicy.requireTournamentViewer(actor, tournament,
            visibleBeyondOrganizer(actor, tournamentId));
        return TournamentAssembler.toData(tournament);
    }

    @Override
    public EntryListResult list(CurrentActor actor, UUID tournamentId, int page, int size) {
        Tournament tournament = find(tournamentId);
        accessPolicy.requireTournamentViewer(actor, tournament,
            visibleBeyondOrganizer(actor, tournamentId));
        List<EntryData> items = entries.findByTournamentId(tournamentId, page * size, size)
            .stream()
            .map(TournamentAssembler::toData)
            .toList();
        return new EntryListResult(items, entries.countByTournamentId(tournamentId));
    }

    private boolean visibleBeyondOrganizer(CurrentActor actor, UUID tournamentId) {
        boolean invited = invitations.findByTournamentIdAndUserId(tournamentId, actor.userId())
            .map(invitation -> ACTIVE_INVITATION_STATUSES.contains(invitation.status()))
            .orElse(false);
        return invited || entries.existsByTournamentIdAndUserId(tournamentId, actor.userId());
    }

    private Tournament find(UUID tournamentId) {
        return tournaments.findById(tournamentId)
            .orElseThrow(() -> new TournamentNotFoundException("Турнир не найден: " + tournamentId));
    }
}
