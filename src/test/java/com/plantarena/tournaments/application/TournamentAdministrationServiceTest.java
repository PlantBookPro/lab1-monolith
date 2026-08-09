package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.application.port.in.CreateTournamentUseCase;
import com.plantarena.tournaments.application.port.in.DeleteTournamentUseCase;
import com.plantarena.tournaments.application.port.in.OpenRegistrationUseCase;
import com.plantarena.tournaments.application.port.in.UpdateTournamentUseCase;
import com.plantarena.tournaments.application.support.FakePlantEligibilityGateway;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTagRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.TournamentStatus;
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

@DisplayName("Администрация турнира: черновик, параметры, открытие, отмена (раздел 7)")
class TournamentAdministrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations = new InMemoryInvitationRepository();
    private final InMemoryTagRepository tags = new InMemoryTagRepository();
    private final FakePlantEligibilityGateway eligibility = new FakePlantEligibilityGateway();
    private final TournamentAdministrationService service = new TournamentAdministrationService(
        tournaments, tags, invitations, eligibility, new TournamentsAccessPolicy(),
        Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID organizerId = UUID.randomUUID();
    private final CurrentActor organizer =
        CurrentActor.identified(organizerId, Set.of(AppRole.USER, AppRole.MODERATOR));
    private final CurrentActor stranger =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER));

    private UUID createDraft() {
        return service.create(organizer, new CreateTournamentUseCase.CreateTournamentCommand(
            "Турнир", "Описание", NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2,
            Set.of())).id();
    }

    @Test
    @DisplayName("создание: модератор; обычный пользователь — 403; неизвестный тег — 404")
    void создание() {
        assertThat(service.create(organizer, new CreateTournamentUseCase.CreateTournamentCommand(
                "Турнир", "Описание", NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2,
                Set.of())).status()).isEqualTo("DRAFT");

        assertThatThrownBy(() -> service.create(stranger,
            new CreateTournamentUseCase.CreateTournamentCommand(
                "Турнир", null, NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2, Set.of())))
            .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> service.create(organizer,
            new CreateTournamentUseCase.CreateTournamentCommand(
                "Турнир", null, NOW.plusSeconds(3600), Duration.ofHours(1), 0.5, 2,
                Set.of(UUID.randomUUID()))))
            .isInstanceOf(TagNotFoundException.class);
    }

    @Test
    @DisplayName("update: параметры в DRAFT; после открытия параметры 409, описание 200")
    void update_правила() {
        UUID tournamentId = createDraft();
        service.update(organizer, tournamentId, new UpdateTournamentUseCase.UpdateTournamentCommand(
            "Новое имя", null, null, null, null, null, null));
        assertThat(tournaments.findById(tournamentId).orElseThrow().name())
            .isEqualTo("Новое имя");

        service.openRegistration(organizer, tournamentId);
        assertThatThrownBy(() -> service.update(organizer, tournamentId,
            new UpdateTournamentUseCase.UpdateTournamentCommand("Н", null, null, null,
                null, 3, null)))
            .isInstanceOf(TournamentStateConflictException.class);

        service.update(organizer, tournamentId,
            new UpdateTournamentUseCase.UpdateTournamentCommand(null, "Безопасное", null,
                null, null, null, null));
        assertThat(tournaments.findById(tournamentId).orElseThrow().description())
            .isEqualTo("Безопасное");

        assertThatThrownBy(() -> service.update(stranger, tournamentId,
            new UpdateTournamentUseCase.UpdateTournamentCommand(null, "Чужое", null,
                null, null, null, null)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("update: описание отменённого турнира — 409, не 500")
    void update_описание_отменённого() {
        UUID tournamentId = createDraft();
        service.cancel(organizer, tournamentId);

        assertThatThrownBy(() -> service.update(organizer, tournamentId,
            new UpdateTournamentUseCase.UpdateTournamentCommand(null, "Новое", null,
                null, null, null, null)))
            .isInstanceOf(TournamentStateConflictException.class);
    }

    @Test
    @DisplayName("delete: пустой DRAFT удаляется; с приглашениями или после открытия — 409")
    void delete_правила() {
        UUID empty = createDraft();
        service.delete(organizer, empty);
        assertThat(tournaments.findById(empty)).isEmpty();

        UUID withInvitations = createDraft();
        invitations.save(Invitation.invite(withInvitations, UUID.randomUUID(),
            organizerId, NOW));
        assertThatThrownBy(() -> service.delete(organizer, withInvitations))
            .isInstanceOf(TournamentStateConflictException.class);

        UUID opened = createDraft();
        service.openRegistration(organizer, opened);
        assertThatThrownBy(() -> service.delete(organizer, opened))
            .isInstanceOf(TournamentStateConflictException.class);
    }

    @Test
    @DisplayName("openRegistration: DRAFT → REGISTRATION_OPEN; после дедлайна — 409")
    void open_registration() {
        UUID tournamentId = createDraft();
        service.openRegistration(organizer, tournamentId);
        assertThat(tournaments.findById(tournamentId).orElseThrow().status())
            .isEqualTo(TournamentStatus.REGISTRATION_OPEN);

        assertThatThrownBy(() -> service.openRegistration(organizer, tournamentId))
            .isInstanceOf(TournamentStateConflictException.class);
    }

    @Test
    @DisplayName("cancel: до RUNNING освобождает резервы принятых заявок; RUNNING — 409")
    void cancel_и_резервы() {
        UUID tournamentId = createDraft();
        UUID userId = UUID.randomUUID();
        Invitation accepted = invitations.save(Invitation.invite(tournamentId, userId,
            organizerId, NOW));
        accepted.accept(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true, NOW);
        invitations.save(accepted);

        service.cancel(organizer, tournamentId);
        assertThat(tournaments.findById(tournamentId).orElseThrow().status())
            .isEqualTo(TournamentStatus.CANCELLED);
        assertThat(eligibility.released).contains(accepted.reservationId());

        UUID runningId = createDraft();
        service.openRegistration(organizer, runningId);
        TournamentAdministrationServiceTest.start(tournaments, runningId, NOW);
        assertThatThrownBy(() -> service.cancel(organizer, runningId))
            .isInstanceOf(TournamentStateConflictException.class);
    }

    /** Помощник: перевод турнира в RUNNING напрямую через домен (для теста cancel). */
    private static void start(InMemoryTournamentRepository tournaments, UUID id, Instant now) {
        var tournament = tournaments.findById(id).orElseThrow();
        tournament.start(tournament.registrationDeadline(), 2);
        tournaments.save(tournament);
    }
}
