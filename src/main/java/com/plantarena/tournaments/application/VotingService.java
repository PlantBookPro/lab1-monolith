package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import com.plantarena.tournaments.application.port.in.VotingUseCase;
import com.plantarena.tournaments.application.port.out.AbuseSignals;
import com.plantarena.tournaments.domain.GuestSession;
import com.plantarena.tournaments.domain.GuestSessionRepository;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingSubject;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.WindowScope;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class VotingService implements VotingUseCase {

    private static final Set<InvitationStatus> ACTIVE_INVITATION_STATUSES = Set.of(
        InvitationStatus.INVITED, InvitationStatus.ACCEPTED_PENDING_MODERATION,
        InvitationStatus.READY);

    private final VotingWindowRepository windows;
    private final TournamentRepository tournaments;
    private final InvitationRepository invitations;
    private final TournamentEntryRepository entries;
    private final GuestSessionRepository guestSessions;
    private final TournamentsAccessPolicy accessPolicy;
    private final FixedWindowRateLimiter rateLimiter;
    private final GuestSessionsSettings guestSettings;
    private final AbuseSignals abuseSignals;
    private final Clock clock;

    public VotingService(VotingWindowRepository windows, TournamentRepository tournaments,
                         InvitationRepository invitations, TournamentEntryRepository entries,
                         GuestSessionRepository guestSessions, TournamentsAccessPolicy accessPolicy,
                         FixedWindowRateLimiter rateLimiter, GuestSessionsSettings guestSettings,
                         AbuseSignals abuseSignals, Clock clock) {
        this.windows = windows;
        this.tournaments = tournaments;
        this.invitations = invitations;
        this.entries = entries;
        this.guestSessions = guestSessions;
        this.accessPolicy = accessPolicy;
        this.rateLimiter = rateLimiter;
        this.guestSettings = guestSettings;
        this.abuseSignals = abuseSignals;
        this.clock = clock;
    }

    @Override
    @Transactional
    public long cast(CurrentActor actor, String guestToken, UUID windowId, UUID entryId,
                     String value) {
        VotingSubject subject = subjectOf(actor, guestToken);
        VoteValue voteValue = parseValue(value);
        VotingWindow window = lock(windowId);
        requireVoter(actor, findTournament(window.tournamentId()), window);
        checkVote(window, entryId, actor);
        long score = window.castVote(subject, entryId, voteValue, clock.instant());
        windows.save(window);
        return score;
    }

    @Override
    @Transactional
    public long remove(CurrentActor actor, String guestToken, UUID windowId, UUID entryId) {
        VotingSubject subject = subjectOf(actor, guestToken);
        VotingWindow window = lock(windowId);
        requireVoter(actor, findTournament(window.tournamentId()), window);
        checkVote(window, entryId, actor);
        long score = window.removeVote(subject, entryId, clock.instant());
        windows.save(window);
        return score;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> myVote(CurrentActor actor, String guestToken, UUID windowId,
                                   UUID entryId) {
        VotingSubject subject = subjectOf(actor, guestToken);
        VotingWindow window = windows.findById(windowId)
            .orElseThrow(() -> new WindowNotFoundException("Окно не найдено: " + windowId));
        requireVoter(actor, findTournament(window.tournamentId()), window);
        if (!window.hasEntry(entryId)) {
            throw new EntryNotInWindowException("Участие не входит в окно: " + entryId);
        }
        VoteValue value = window.myVote(subject.subjectKey(), entryId);
        return Optional.ofNullable(value).map(Enum::name);
    }

    
    private VotingSubject subjectOf(CurrentActor actor, String guestToken) {
        if (actor != null && !actor.isGuest()) {
            return VotingSubject.user(actor.userId());
        }
        String token = guestToken == null ? "" : guestToken.trim();
        if (token.isEmpty()) {
            throw new NotIdentifiedException(
                "Голосование требует пользователя или гостевой токен (X-Guest-Token)");
        }
        GuestSession session = guestSessions.findByTokenHash(GuestTokens.sha256Hex(token))
            .filter(active -> active.isActive(clock.instant()))
            .orElseThrow(() -> new NotIdentifiedException(
                "Гостевая сессия отсутствует или истекла"));
        try {
            rateLimiter.check("guest-vote:" + session.id(),
                guestSettings.voteLimitPerMinute());
        } catch (RateLimitExceededException e) {
            abuseSignals.signal("guest-vote-limit", "sessionId=" + session.id());
            throw e;
        }
        return VotingSubject.guest(session.id());
    }

    private VoteValue parseValue(String value) {
        if (value == null) {
            throw new UnknownVoteValueException("Значение голоса обязательно (LIKE/DISLIKE)");
        }
        try {
            return VoteValue.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new UnknownVoteValueException(
                "Неизвестное значение голоса: " + value + " (допустимо: LIKE, DISLIKE)");
        }
    }

    private void requireVoter(CurrentActor actor, Tournament tournament, VotingWindow window) {
        if (window.scope() == WindowScope.PRIVATE) {
            if (actor == null || actor.isGuest()) {
                
                throw new TournamentNotFoundException(
                    "Турнир не найден: " + tournament.id());
            }
            accessPolicy.requireTournamentViewer(actor, tournament,
                visibleBeyondOrganizer(actor, tournament.id()));
            if (!entries.existsByTournamentIdAndUserId(tournament.id(), actor.userId())) {
                throw new AccessDeniedException(
                    "Голосовать может только участник, допущенный к старту (допущение 9)");
            }
            return;
        }
        
        
    }

    private void checkVote(VotingWindow window, UUID entryId, CurrentActor actor) {
        if (!window.isAcceptingVotes(clock.instant())) {
            throw new VotingClosedException(
                "Окно закрыто или дедлайн истёк (интервал [opensAt, closesAt))");
        }
        if (!window.hasEntry(entryId)) {
            throw new EntryNotInWindowException("Участие не входит в окно: " + entryId);
        }
        if (window.userIdOfEntry(entryId).equals(actor.userId())) {
            throw new SelfVoteForbiddenException("Самоголосование запрещено (допущение 6)");
        }
    }

    private boolean visibleBeyondOrganizer(CurrentActor actor, UUID tournamentId) {
        boolean invited = invitations.findByTournamentIdAndUserId(tournamentId, actor.userId())
            .map(invitation -> ACTIVE_INVITATION_STATUSES.contains(invitation.status()))
            .orElse(false);
        return invited || entries.existsByTournamentIdAndUserId(tournamentId, actor.userId());
    }

    private VotingWindow lock(UUID windowId) {
        return windows.findByIdForUpdate(windowId)
            .orElseThrow(() -> new WindowNotFoundException("Окно не найдено: " + windowId));
    }

    private Tournament findTournament(UUID tournamentId) {
        return tournaments.findById(tournamentId)
            .orElseThrow(() -> new TournamentNotFoundException(
                "Турнир не найден: " + tournamentId));
    }
}
