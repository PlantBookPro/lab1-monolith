package com.plantarena.tournaments.application;

import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.TournamentData;
import com.plantarena.tournaments.application.port.in.CancelTournamentUseCase;
import com.plantarena.tournaments.application.port.in.CreateTournamentUseCase;
import com.plantarena.tournaments.application.port.in.DeleteTournamentUseCase;
import com.plantarena.tournaments.application.port.in.OpenRegistrationUseCase;
import com.plantarena.tournaments.application.port.in.UpdateTournamentUseCase;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationRepository;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.TagRepository;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.TournamentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Администрация турнира (раздел 7): черновик, параметры (только DRAFT),
 * безопасное описание, удаление пустого черновика, открытие регистрации,
 * отмена до RUNNING. Отмена освобождает резервы принятых заявок командой
 * plants в той же tx (ADR-010).
 */
@Service
public class TournamentAdministrationService implements CreateTournamentUseCase,
        UpdateTournamentUseCase, DeleteTournamentUseCase, OpenRegistrationUseCase,
        CancelTournamentUseCase {

    private final TournamentRepository tournaments;
    private final TagRepository tags;
    private final InvitationRepository invitations;
    private final PlantEligibilityGateway eligibility;
    private final TournamentsAccessPolicy accessPolicy;
    private final Clock clock;

    public TournamentAdministrationService(TournamentRepository tournaments,
                                           TagRepository tags,
                                           InvitationRepository invitations,
                                           PlantEligibilityGateway eligibility,
                                           TournamentsAccessPolicy accessPolicy, Clock clock) {
        this.tournaments = tournaments;
        this.tags = tags;
        this.invitations = invitations;
        this.eligibility = eligibility;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TournamentData create(CurrentActor actor, CreateTournamentCommand command) {
        accessPolicy.requireModeratorOrAdmin(actor);
        Set<UUID> tagIds = resolveTags(command.tagIds());
        Tournament draft = Tournament.createDraft(actor.userId(), command.name(),
            command.description(), command.registrationDeadline(), command.roundDuration(),
            command.eliminationFraction(), command.minParticipants(), tagIds, clock.instant());
        return TournamentAssembler.toData(tournaments.save(draft));
    }

    @Override
    @Transactional
    public TournamentData update(CurrentActor actor, UUID tournamentId,
                                 UpdateTournamentCommand command) {
        accessPolicy.requireIdentified(actor);
        Tournament tournament = find(tournamentId);
        accessPolicy.requireOrganizer(actor, tournament);
        if (command.description() != null) {
            if (tournament.status() != TournamentStatus.DRAFT
                    && tournament.status() != TournamentStatus.REGISTRATION_OPEN
                    && tournament.status() != TournamentStatus.RUNNING) {
                throw new TournamentStateConflictException(
                    "Описание меняется только в DRAFT/REGISTRATION_OPEN/RUNNING, текущий статус: "
                        + tournament.status());
            }
            tournament.updateDescription(command.description());
        }
        if (isParameterUpdate(command)) {
            requireDraft(tournament, "Параметры меняются только в DRAFT");
            tournament.updateParameters(
                command.name() == null ? tournament.name() : command.name(),
                command.registrationDeadline() == null
                    ? tournament.registrationDeadline() : command.registrationDeadline(),
                command.roundDuration() == null
                    ? tournament.roundDuration() : command.roundDuration(),
                command.eliminationFraction() == null
                    ? tournament.eliminationFraction() : command.eliminationFraction(),
                command.minParticipants() == null
                    ? tournament.minParticipants() : command.minParticipants(),
                clock.instant());
        }
        if (command.tagIds() != null) {
            requireDraft(tournament, "Теги меняются только в DRAFT");
            tournament.replaceTags(resolveTags(command.tagIds()));
        }
        return TournamentAssembler.toData(tournaments.save(tournament));
    }

    @Override
    @Transactional
    public void delete(CurrentActor actor, UUID tournamentId) {
        accessPolicy.requireIdentified(actor);
        Tournament tournament = find(tournamentId);
        accessPolicy.requireOrganizer(actor, tournament);
        requireDraft(tournament, "Удалять можно только черновик");
        if (invitations.existsByTournamentId(tournamentId)) {
            throw new TournamentStateConflictException(
                "Черновик с приглашениями не удаляется: отзови приглашения или отмени турнир");
        }
        tournaments.delete(tournamentId);
    }

    @Override
    @Transactional
    public TournamentData openRegistration(CurrentActor actor, UUID tournamentId) {
        accessPolicy.requireIdentified(actor);
        Tournament tournament = find(tournamentId);
        accessPolicy.requireOrganizer(actor, tournament);
        if (tournament.status() != TournamentStatus.DRAFT) {
            throw new TournamentStateConflictException(
                "Открыть регистрацию можно только из DRAFT, текущий статус: "
                    + tournament.status());
        }
        if (!clock.instant().isBefore(tournament.registrationDeadline())) {
            throw new TournamentStateConflictException("Нельзя открыть регистрацию после дедлайна");
        }
        tournament.openRegistration(clock.instant());
        return TournamentAssembler.toData(tournaments.save(tournament));
    }

    @Override
    @Transactional
    public TournamentData cancel(CurrentActor actor, UUID tournamentId) {
        accessPolicy.requireIdentified(actor);
        Tournament tournament = find(tournamentId);
        accessPolicy.requireOrganizer(actor, tournament);
        if (tournament.status() == TournamentStatus.RUNNING
                || tournament.status() == TournamentStatus.FINISHED) {
            throw new TournamentStateConflictException(
                "Отмена активного турнира в первой версии запрещена (раздел 7)");
        }
        if (tournament.status() == TournamentStatus.CANCELLED) {
            throw new TournamentStateConflictException("Турнир уже отменён");
        }
        tournament.cancel(clock.instant());
        tournaments.save(tournament);
        releaseAcceptedReservations(tournamentId);
        return TournamentAssembler.toData(tournament);
    }

    /** Освободить резервы принятых заявок (отмена — ADR-010, та же tx). */
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

    private Set<UUID> resolveTags(Set<UUID> tagIds) {
        Set<UUID> resolved = new HashSet<>();
        for (UUID tagId : tagIds == null ? Set.<UUID>of() : tagIds) {
            tags.findById(tagId).orElseThrow(
                () -> new TagNotFoundException("Тег не найден: " + tagId));
            resolved.add(tagId);
        }
        return resolved;
    }

    private boolean isParameterUpdate(UpdateTournamentCommand command) {
        return command.name() != null || command.registrationDeadline() != null
            || command.roundDuration() != null || command.eliminationFraction() != null
            || command.minParticipants() != null;
    }

    private void requireDraft(Tournament tournament, String message) {
        if (tournament.status() != TournamentStatus.DRAFT) {
            throw new TournamentStateConflictException(message + ", текущий статус: "
                + tournament.status());
        }
    }

    private Tournament find(UUID tournamentId) {
        return tournaments.findById(tournamentId)
            .orElseThrow(() -> new TournamentNotFoundException("Турнир не найден: " + tournamentId));
    }
}
