package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.AcceptInvitationUseCase;
import com.plantarena.tournaments.application.port.in.CreateTournamentUseCase;
import com.plantarena.tournaments.application.port.in.DeclineInvitationUseCase;
import com.plantarena.tournaments.application.port.in.InviteUserUseCase;
import com.plantarena.tournaments.application.port.in.OpenRegistrationUseCase;
import com.plantarena.tournaments.application.port.in.RevokeInvitationUseCase;
import com.plantarena.tournaments.application.port.out.ParticipantDirectoryGateway;
import com.plantarena.tournaments.application.port.out.PlantDirectoryGateway;
import com.plantarena.tournaments.application.support.FakeEventPublisher;
import com.plantarena.tournaments.application.support.FakeParticipantDirectoryGateway;
import com.plantarena.tournaments.application.support.FakePlantDirectoryGateway;
import com.plantarena.tournaments.application.support.FakePlantEligibilityGateway;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTagRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.api.event.InvitationCreatedEvent;
import com.plantarena.tournaments.domain.InvitationStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Приглашения: пригласить/отозвать/принять/отказаться (раздел 7)")
class InvitationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations =
        new InMemoryInvitationRepository(tournaments);
    private final FakePlantEligibilityGateway eligibility = new FakePlantEligibilityGateway();
    private final FakePlantDirectoryGateway plantDirectory = new FakePlantDirectoryGateway();
    private final FakeParticipantDirectoryGateway participants =
        new FakeParticipantDirectoryGateway();
    private final FakeEventPublisher events = new FakeEventPublisher();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TournamentsAccessPolicy accessPolicy = new TournamentsAccessPolicy();
    private final TournamentAdministrationService administration =
        new TournamentAdministrationService(tournaments, new InMemoryTagRepository(),
            invitations, eligibility, accessPolicy, clock);
    private final InvitationService service = new InvitationService(tournaments, invitations,
        eligibility, plantDirectory, participants, accessPolicy, events, clock);

    private final UUID organizerId = UUID.randomUUID();
    private final CurrentActor organizer =
        CurrentActor.identified(organizerId, Set.of(AppRole.USER, AppRole.MODERATOR));
    private final UUID userId = UUID.randomUUID();
    private final CurrentActor user = CurrentActor.identified(userId, Set.of(AppRole.USER));
    private final CurrentActor stranger =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER));

    private UUID openTournament() {
        UUID tournamentId = administration.create(organizer,
            new CreateTournamentUseCase.CreateTournamentCommand("Турнир", null,
                NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, Set.of())).id();
        administration.openRegistration(organizer, tournamentId);
        return tournamentId;
    }

    @Test
    @DisplayName("invite: INVITED + событие; дубль пары 409; неизвестный пользователь 404")
    void invite_правила() {
        UUID tournamentId = openTournament();
        participants.knownUsers.add(userId);

        var invitation = service.invite(organizer, tournamentId, userId);
        assertThat(invitation.status()).isEqualTo("INVITED");
        assertThat(events.published).anySatisfy(event ->
            assertThat(event).isInstanceOf(InvitationCreatedEvent.class));

        assertThatThrownBy(() -> service.invite(organizer, tournamentId, userId))
            .isInstanceOf(DuplicateInvitationException.class);
        assertThatThrownBy(() -> service.invite(organizer, tournamentId, UUID.randomUUID()))
            .isInstanceOf(UnknownUserException.class);
    }

    @Test
    @DisplayName("invite: после дедлайна — 409 RegistrationClosed")
    void invite_после_дедлайна() {
        UUID tournamentId = administration.create(organizer,
            new CreateTournamentUseCase.CreateTournamentCommand("Турнир", null,
                NOW.plusSeconds(1), Duration.ofHours(1), 0.5, 2, Set.of())).id();
        administration.openRegistration(organizer, tournamentId);
        participants.knownUsers.add(userId);

        // дедлайн прошёл: время фиксировано на NOW.plusSeconds(2)
        InvitationService lateService = new InvitationService(tournaments, invitations,
            eligibility, plantDirectory, participants, accessPolicy, events,
            Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));
        assertThatThrownBy(() -> lateService.invite(organizer, tournamentId, userId))
            .isInstanceOf(RegistrationClosedException.class);
    }

    @Test
    @DisplayName("accept: PENDING-растение → ACCEPTED_PENDING_MODERATION с резервом по свежему ключу")
    void accept_pending() {
        UUID tournamentId = openTournament();
        participants.knownUsers.add(userId);
        UUID invitationId = service.invite(organizer, tournamentId, userId).id();
        UUID plantId = UUID.randomUUID();
        plantDirectory.plants.put(plantId,
            new PlantDirectoryGateway.PlantSnapshot(plantId, userId, false));

        var accepted = service.accept(user, invitationId, plantId);

        assertThat(accepted.status()).isEqualTo("ACCEPTED_PENDING_MODERATION");
        assertThat(eligibility.reservationsByKey).hasSize(1);
        UUID reservationId = invitations.findById(invitationId).orElseThrow().reservationId();
        assertThat(eligibility.reservationsByKey).containsValue(reservationId);

        // идемпотентный повтор с тем же растением — без нового резерва
        service.accept(user, invitationId, plantId);
        assertThat(eligibility.reservationsByKey).hasSize(1);
    }

    @Test
    @DisplayName("accept: APPROVED-растение → сразу READY; чужое приглашение скрыто (404)")
    void accept_варианты() {
        UUID tournamentId = openTournament();
        participants.knownUsers.add(userId);
        UUID invitationId = service.invite(organizer, tournamentId, userId).id();
        UUID plantId = UUID.randomUUID();
        plantDirectory.plants.put(plantId,
            new PlantDirectoryGateway.PlantSnapshot(plantId, userId, true));

        assertThat(service.accept(user, invitationId, plantId).status()).isEqualTo("READY");

        // чужое приглашение скрыто: не адресат и посторонний получают 404
        participants.knownUsers.add(stranger.userId());
        UUID strangerInvitationId = service.invite(organizer, tournamentId,
            stranger.userId()).id();
        assertThatThrownBy(() -> service.accept(stranger, invitationId, plantId))
            .isInstanceOf(InvitationNotFoundException.class);
        assertThatThrownBy(() -> service.accept(user, strangerInvitationId, plantId))
            .isInstanceOf(InvitationNotFoundException.class);
    }

    @Test
    @DisplayName("accept: конфликт резерва и нерезервируемое растение — 409")
    void accept_конфликты() {
        UUID tournamentId = openTournament();
        participants.knownUsers.add(userId);
        UUID invitationId = service.invite(organizer, tournamentId, userId).id();
        UUID plantId = UUID.randomUUID();
        plantDirectory.plants.put(plantId,
            new PlantDirectoryGateway.PlantSnapshot(plantId, userId, true));

        eligibility.conflictOnReserve = true;
        assertThatThrownBy(() -> service.accept(user, invitationId, plantId))
            .isInstanceOf(ImageAlreadyReservedException.class);
        eligibility.conflictOnReserve = false;

        eligibility.failOnReserve = true;
        assertThatThrownBy(() -> service.accept(user, invitationId, plantId))
            .isInstanceOf(PlantNotReservableException.class);
    }

    @Test
    @DisplayName("decline: освобождает резерв; после старта — 409")
    void decline_правила() {
        UUID tournamentId = openTournament();
        participants.knownUsers.add(userId);
        UUID invitationId = service.invite(organizer, tournamentId, userId).id();
        UUID plantId = UUID.randomUUID();
        plantDirectory.plants.put(plantId,
            new PlantDirectoryGateway.PlantSnapshot(plantId, userId, false));
        service.accept(user, invitationId, plantId);
        UUID reservationId = invitations.findById(invitationId).orElseThrow().reservationId();

        assertThat(service.decline(user, invitationId).status()).isEqualTo("DECLINED");
        assertThat(eligibility.isReleased(reservationId)).isTrue();
    }

    @Test
    @DisplayName("decline: из READY — 200, резерв подтверждённой заявки освобождён")
    void decline_из_ready() {
        UUID tournamentId = openTournament();
        participants.knownUsers.add(userId);
        UUID invitationId = service.invite(organizer, tournamentId, userId).id();
        UUID plantId = UUID.randomUUID();
        plantDirectory.plants.put(plantId,
            new PlantDirectoryGateway.PlantSnapshot(plantId, userId, true));
        service.accept(user, invitationId, plantId);
        UUID reservationId = invitations.findById(invitationId).orElseThrow().reservationId();

        var declined = service.decline(user, invitationId);

        assertThat(declined.status()).isEqualTo("DECLINED");
        assertThat(eligibility.isReleased(reservationId)).isTrue();
    }

    @Test
    @DisplayName("revoke: только INVITED и до дедлайна; принятое — 409")
    void revoke_правила() {
        UUID tournamentId = openTournament();
        participants.knownUsers.add(userId);
        UUID invitationId = service.invite(organizer, tournamentId, userId).id();

        service.revoke(organizer, tournamentId, invitationId);
        assertThat(invitations.findById(invitationId).orElseThrow().status())
            .isEqualTo(InvitationStatus.REVOKED);

        UUID acceptedUserId = UUID.randomUUID();
        participants.knownUsers.add(acceptedUserId);
        UUID acceptedId = service.invite(organizer, tournamentId, acceptedUserId).id();
        invitations.findById(acceptedId).orElseThrow()
            .accept(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true, NOW);
        assertThatThrownBy(() -> service.revoke(organizer, tournamentId, acceptedId))
            .isInstanceOf(TournamentStateConflictException.class);
    }
}
