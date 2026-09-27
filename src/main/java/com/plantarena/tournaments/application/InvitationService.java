package com.plantarena.tournaments.application;

import com.plantarena.shared.event.IntegrationEventPublisher;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.InvitationData;
import com.plantarena.tournaments.api.event.InvitationCreatedEvent;
import com.plantarena.tournaments.application.port.in.AcceptInvitationUseCase;
import com.plantarena.tournaments.application.port.in.DeclineInvitationUseCase;
import com.plantarena.tournaments.application.port.in.InviteUserUseCase;
import com.plantarena.tournaments.application.port.in.ListInvitationsUseCase;
import com.plantarena.tournaments.application.port.in.RevokeInvitationUseCase;
import com.plantarena.tournaments.application.port.out.ParticipantDirectoryGateway;
import com.plantarena.tournaments.application.port.out.PlantDirectoryGateway;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.TournamentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Приглашения (раздел 7): пригласить/отозвать (организатор, до дедлайна),
 * принять с растением (адресат, до дедлайна; резерв в той же tx — ADR-010),
 * отказаться. Принятие: APPROVED-растение → READY сразу, идущая модерация →
 * ACCEPTED_PENDING_MODERATION (не допуск к голосованию).
 */
@Service
public class InvitationService implements InviteUserUseCase, RevokeInvitationUseCase,
        AcceptInvitationUseCase, DeclineInvitationUseCase, ListInvitationsUseCase {

    private final TournamentRepository tournaments;
    private final InvitationRepository invitations;
    private final PlantEligibilityGateway eligibility;
    private final PlantDirectoryGateway plantDirectory;
    private final ParticipantDirectoryGateway participants;
    private final TournamentsAccessPolicy accessPolicy;
    private final IntegrationEventPublisher eventPublisher;
    private final Clock clock;

    public InvitationService(TournamentRepository tournaments,
                             InvitationRepository invitations,
                             PlantEligibilityGateway eligibility,
                             PlantDirectoryGateway plantDirectory,
                             ParticipantDirectoryGateway participants,
                             TournamentsAccessPolicy accessPolicy,
                             IntegrationEventPublisher eventPublisher, Clock clock) {
        this.tournaments = tournaments;
        this.invitations = invitations;
        this.eligibility = eligibility;
        this.plantDirectory = plantDirectory;
        this.participants = participants;
        this.accessPolicy = accessPolicy;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public InvitationData invite(CurrentActor actor, UUID tournamentId, UUID userId) {
        accessPolicy.requireIdentified(actor);
        Tournament tournament = findTournament(tournamentId);
        accessPolicy.requireOrganizer(actor, tournament);
        if (!tournament.canInvite(clock.instant())) {
            throw new RegistrationClosedException(
                "Приглашать можно в DRAFT/REGISTRATION_OPEN до дедлайна");
        }
        if (!participants.isKnownUser(userId)) {
            throw new UnknownUserException("Неизвестный пользователь: " + userId);
        }
        if (invitations.findByTournamentIdAndUserId(tournamentId, userId).isPresent()) {
            throw new DuplicateInvitationException(
                "Пользователь уже приглашён в турнир: " + userId);
        }
        Invitation invitation = invitations.save(
            Invitation.invite(tournamentId, userId, actor.userId(), clock.instant()));
        publishInvitationCreated(invitation);
        return TournamentAssembler.toData(invitation);
    }

    @Override
    @Transactional
    public void revoke(CurrentActor actor, UUID tournamentId, UUID invitationId) {
        accessPolicy.requireIdentified(actor);
        Tournament tournament = findTournament(tournamentId);
        accessPolicy.requireOrganizer(actor, tournament);
        if (!tournament.canInvite(clock.instant())) {
            throw new RegistrationClosedException("Отзывать приглашения можно до дедлайна");
        }
        Invitation invitation = findInvitation(invitationId);
        if (!invitation.tournamentId().equals(tournamentId)) {
            throw new InvitationNotFoundException("Приглашение не найдено: " + invitationId);
        }
        if (invitation.status() != InvitationStatus.INVITED) {
            throw new TournamentStateConflictException(
                "Отзывать можно только не принятые приглашения, статус: " + invitation.status());
        }
        invitation.revoke(clock.instant());
        invitations.save(invitation);
    }

    @Override
    @Transactional
    public InvitationData accept(CurrentActor actor, UUID invitationId, UUID plantId) {
        accessPolicy.requireIdentified(actor);
        Invitation invitation = findInvitation(invitationId);
        accessPolicy.requireAddressee(actor, invitation);
        Tournament tournament = findTournament(invitation.tournamentId());
        if (!tournament.isAcceptingNow(clock.instant())) {
            throw new RegistrationClosedException(
                "Принимать приглашения можно только в REGISTRATION_OPEN до дедлайна");
        }
        if (invitation.status() == InvitationStatus.ACCEPTED_PENDING_MODERATION
                || invitation.status() == InvitationStatus.READY) {
            if (plantId != null && plantId.equals(invitation.submittedPlantId())) {
                return TournamentAssembler.toData(invitation); // идемпотентный повтор
            }
            throw new TournamentStateConflictException(
                "Приглашение уже принято; сначала откажитесь, затем подайте другое растение");
        }
        if (invitation.status() != InvitationStatus.INVITED) {
            throw new TournamentStateConflictException(
                "Приглашение уже закрыто: " + invitation.status());
        }
        PlantDirectoryGateway.PlantSnapshot plant = plantDirectory.findById(plantId)
            .orElseThrow(() -> new InvitedPlantNotFoundException("Растение не найдено: " + plantId));
        UUID submissionKey = UUID.randomUUID(); // свежий ключ на попытку (дизайн, решение 3)
        UUID reservationId = eligibility.reserve(actor.userId(), plantId, submissionKey);
        invitation.accept(plantId, reservationId, submissionKey, plant.approved(),
            clock.instant());
        return TournamentAssembler.toData(invitations.save(invitation));
    }

    @Override
    @Transactional
    public InvitationData decline(CurrentActor actor, UUID invitationId) {
        accessPolicy.requireIdentified(actor);
        Invitation invitation = findInvitation(invitationId);
        accessPolicy.requireAddressee(actor, invitation);
        Tournament tournament = findTournament(invitation.tournamentId());
        if (tournament.status() != TournamentStatus.DRAFT
                && tournament.status() != TournamentStatus.REGISTRATION_OPEN) {
            throw new TournamentStateConflictException("Отказ возможен только до старта");
        }
        if (invitation.status() == InvitationStatus.DECLINED) {
            return TournamentAssembler.toData(invitation); // идемпотентно
        }
        if (invitation.status() == InvitationStatus.REVOKED
                || invitation.status() == InvitationStatus.EXPIRED) {
            throw new TournamentStateConflictException(
                "Приглашение уже закрыто: " + invitation.status());
        }
        if (invitation.reservationId() != null) {
            eligibility.release(invitation.reservationId());
        }
        invitation.decline(clock.instant());
        return TournamentAssembler.toData(invitations.save(invitation));
    }

    @Override
    @Transactional(readOnly = true)
    public InvitationListResult list(CurrentActor actor, UUID tournamentId, int page, int size) {
        accessPolicy.requireIdentified(actor);
        Tournament tournament = findTournament(tournamentId);
        accessPolicy.requireOrganizer(actor, tournament);
        List<InvitationData> items = invitations
            .findByTournamentId(tournamentId, page * size, size).stream()
            .map(TournamentAssembler::toData)
            .toList();
        return new InvitationListResult(items, invitations.countByTournamentId(tournamentId));
    }

    @Override
    @Transactional(readOnly = true)
    public InvitationListResult listMine(CurrentActor actor, int page, int size) {
        accessPolicy.requireIdentified(actor);
        List<InvitationData> items =
            invitations.findByUserId(actor.userId(), page * size, size).stream()
                .map(TournamentAssembler::toData)
                .toList();
        return new InvitationListResult(items, invitations.countByUserId(actor.userId()));
    }

    private void publishInvitationCreated(Invitation invitation) {
        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new InvitationCreatedEvent(eventId, InvitationCreatedEvent.TYPE,
            InvitationCreatedEvent.SCHEMA_VERSION, invitation.id(), invitation.version(),
            clock.instant(), eventId, new InvitationCreatedEvent.Payload(invitation.id(),
                invitation.tournamentId(), invitation.userId())));
    }

    private Tournament findTournament(UUID tournamentId) {
        return tournaments.findById(tournamentId)
            .orElseThrow(() -> new TournamentNotFoundException("Турнир не найден: " + tournamentId));
    }

    private Invitation findInvitation(UUID invitationId) {
        return invitations.findById(invitationId)
            .orElseThrow(() -> new InvitationNotFoundException(
                "Приглашение не найдено: " + invitationId));
    }
}
