package com.plantarena.tournaments.application;

import com.plantarena.shared.event.IntegrationEventPublisher;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import com.plantarena.tournaments.api.event.TournamentStartedEvent;
import com.plantarena.tournaments.application.port.in.StartTournamentUseCase;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.TournamentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Один use case старта для ручки и scheduler'а (раздел 7, дизайн итерации 5):
 * start — ручка организатора (после дедлайна, конфликт состояний — 409);
 * startDue — due-турниры из БД, каждый в отдельной короткой tx
 * (TransactionTemplate): один сбой не блокирует остальные. READY ≥
 * minParticipants → RUNNING + TournamentEntry + EXPIRED не-READY +
 * TournamentStarted; иначе CANCELLED (INSUFFICIENT_PARTICIPANTS) с
 * освобождением резервов. Растения не погибают (раздел 7).
 */
@Service
public class StartTournamentService implements StartTournamentUseCase {

    private static final Logger log = LoggerFactory.getLogger(StartTournamentService.class);

    private final TournamentRepository tournaments;
    private final InvitationRepository invitations;
    private final TournamentEntryRepository entries;
    private final PlantEligibilityGateway eligibility;
    private final TournamentsAccessPolicy accessPolicy;
    private final IntegrationEventPublisher eventPublisher;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public StartTournamentService(TournamentRepository tournaments,
                                  InvitationRepository invitations,
                                  TournamentEntryRepository entries,
                                  PlantEligibilityGateway eligibility,
                                  TournamentsAccessPolicy accessPolicy,
                                  IntegrationEventPublisher eventPublisher, Clock clock,
                                  TransactionTemplate transactionTemplate) {
        this.tournaments = tournaments;
        this.invitations = invitations;
        this.entries = entries;
        this.eligibility = eligibility;
        this.accessPolicy = accessPolicy;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    @Transactional
    public TournamentData start(CurrentActor actor, UUID tournamentId) {
        accessPolicy.requireIdentified(actor);
        Tournament tournament = find(tournamentId);
        accessPolicy.requireOrganizer(actor, tournament);
        if (tournament.status() != TournamentStatus.REGISTRATION_OPEN) {
            throw new TournamentStateConflictException(
                "Старт возможен только из REGISTRATION_OPEN, текущий статус: "
                    + tournament.status());
        }
        if (clock.instant().isBefore(tournament.registrationDeadline())) {
            throw new TournamentStateConflictException("Старт возможен только после дедлайна");
        }
        return doStart(tournament, clock.instant());
    }

    @Override
    public int startDue(Instant now, int limit) {
        List<UUID> due = tournaments.findDueForStart(now, limit).stream()
            .map(Tournament::id)
            .toList();
        int processed = 0;
        for (UUID tournamentId : due) {
            try {
                transactionTemplate.executeWithoutResult(
                    status -> startDueOne(tournamentId, now));
                processed++;
            } catch (RuntimeException e) {
                // один битый турнир не блокирует остальные; повтор — следующий poll
                log.warn("Старт турнира {} не удался, будет повторён: {}", tournamentId,
                    e.getMessage());
            }
        }
        return processed;
    }

    private void startDueOne(UUID tournamentId, Instant now) {
        Tournament tournament = find(tournamentId);
        if (tournament.status() != TournamentStatus.REGISTRATION_OPEN) {
            return; // уже обработан (идемпотентность повторного poll'а)
        }
        doStart(tournament, now);
    }

    private TournamentData doStart(Tournament tournament, Instant now) {
        List<Invitation> ready =
            invitations.findByTournamentIdAndStatus(tournament.id(), InvitationStatus.READY);
        if (ready.size() < tournament.minParticipants()) {
            tournament.cancelForInsufficientParticipants(now);
            tournaments.save(tournament);
            releaseAcceptedReservations(tournament.id());
            return TournamentAssembler.toData(tournament);
        }
        for (Invitation invitation : ready) {
            // допуск к старту: только APPROVED и действующий резерв (раздел 6)
            eligibility.confirm(invitation.userId(), invitation.submittedPlantId(),
                invitation.reservationId());
        }
        tournament.start(now, ready.size());
        tournaments.save(tournament);
        for (Invitation invitation : ready) {
            entries.save(TournamentEntry.admit(tournament.id(), invitation.userId(),
                invitation.submittedPlantId(), invitation.reservationId(), now));
        }
        expireNotReady(tournament.id(), now);
        publishStarted(tournament, now);
        return TournamentAssembler.toData(tournament);
    }

    private void expireNotReady(UUID tournamentId, Instant now) {
        for (InvitationStatus status : List.of(InvitationStatus.INVITED,
            InvitationStatus.ACCEPTED_PENDING_MODERATION)) {
            for (Invitation invitation
                    : invitations.findByTournamentIdAndStatus(tournamentId, status)) {
                if (invitation.reservationId() != null) {
                    eligibility.release(invitation.reservationId());
                }
                invitation.expire(now);
                invitations.save(invitation);
            }
        }
    }

    private void releaseAcceptedReservations(UUID tournamentId) {
        for (InvitationStatus status : List.of(InvitationStatus.ACCEPTED_PENDING_MODERATION,
            InvitationStatus.READY)) {
            for (Invitation invitation
                    : invitations.findByTournamentIdAndStatus(tournamentId, status)) {
                if (invitation.reservationId() != null) {
                    eligibility.release(invitation.reservationId());
                }
            }
        }
    }

    private void publishStarted(Tournament tournament, Instant now) {
        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new TournamentStartedEvent(eventId, TournamentStartedEvent.TYPE,
            TournamentStartedEvent.SCHEMA_VERSION, tournament.id(), tournament.version(),
            now, eventId, new TournamentStartedEvent.Payload(tournament.id(),
                tournament.creatorId())));
    }

    private Tournament find(UUID tournamentId) {
        return tournaments.findById(tournamentId)
            .orElseThrow(() -> new TournamentNotFoundException("Турнир не найден: " + tournamentId));
    }
}
