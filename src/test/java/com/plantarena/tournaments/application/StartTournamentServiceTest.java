package com.plantarena.tournaments.application;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.tournaments.api.event.TournamentStartedEvent;
import com.plantarena.tournaments.api.event.VotingWindowOpenedEvent;
import com.plantarena.tournaments.application.port.in.CreateTournamentUseCase;
import com.plantarena.tournaments.application.support.FakeEventPublisher;
import com.plantarena.tournaments.application.support.FakePlantEligibilityGateway;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTagRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.application.support.InMemoryVotingWindowRepository;
import com.plantarena.tournaments.domain.Invitation;
import com.plantarena.tournaments.domain.InvitationStatus;
import com.plantarena.tournaments.domain.TournamentStatus;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.WindowStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Старт турнира: один use case для ручки и scheduler'а (раздел 7)")
class StartTournamentServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations =
        new InMemoryInvitationRepository(tournaments);
    private final InMemoryTournamentEntryRepository entries =
        new InMemoryTournamentEntryRepository(tournaments);
    private final InMemoryVotingWindowRepository windows = new InMemoryVotingWindowRepository();
    private final FakePlantEligibilityGateway eligibility = new FakePlantEligibilityGateway();
    private final FakeEventPublisher events = new FakeEventPublisher();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TournamentsAccessPolicy accessPolicy = new TournamentsAccessPolicy();
    private final TournamentAdministrationService administration =
        new TournamentAdministrationService(tournaments, new InMemoryTagRepository(),
            invitations, eligibility, accessPolicy, clock);
    private final StartTournamentService service = new StartTournamentService(tournaments,
        invitations, entries, windows, eligibility, accessPolicy, events, clock,
        transactionTemplate());

    private final UUID organizerId = UUID.randomUUID();
    private final CurrentActor organizer =
        CurrentActor.identified(organizerId, Set.of(AppRole.USER, AppRole.MODERATOR));

    /** TransactionTemplate без менеджера транзакций: выполняет действие сразу (unit). */
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate() {
        return new org.springframework.transaction.support.TransactionTemplate() {
            @Override
            public void executeWithoutResult(java.util.function.Consumer<
                    org.springframework.transaction.TransactionStatus> action) {
                action.accept(new org.springframework.transaction.support.SimpleTransactionStatus());
            }
        };
    }

    private UUID openTournament(Instant deadline) {
        UUID tournamentId = administration.create(organizer,
            new CreateTournamentUseCase.CreateTournamentCommand("Турнир", null, deadline,
                Duration.ofHours(1), 0.5, 2, Set.of())).id();
        administration.openRegistration(organizer, tournamentId);
        return tournamentId;
    }

    private Invitation readyInvitation(UUID tournamentId) {
        Invitation invitation = invitations.save(Invitation.invite(tournamentId,
            UUID.randomUUID(), organizerId, NOW));
        invitation.accept(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true, NOW);
        return invitations.save(invitation);
    }

    @Test
    @DisplayName("старт по дедлайну: RUNNING, entries по READY, не-READY EXPIRED, событие, резервы подтверждены")
    void старт_успешный() {
        UUID tournamentId = openTournament(NOW.plusSeconds(1));
        Invitation ready1 = readyInvitation(tournamentId);
        Invitation ready2 = readyInvitation(tournamentId);
        Invitation invitedOnly = invitations.save(Invitation.invite(tournamentId,
            UUID.randomUUID(), organizerId, NOW));

        var result = service.startDue(NOW.plusSeconds(2), 10);

        assertThat(result).isEqualTo(1);
        assertThat(tournaments.findById(tournamentId).orElseThrow().status())
            .isEqualTo(TournamentStatus.RUNNING);
        assertThat(entries.findByTournamentId(tournamentId, 0, 50)).hasSize(2);
        assertThat(eligibility.confirmed).extracting(call -> call.reservationId())
            .containsExactlyInAnyOrder(ready1.reservationId(), ready2.reservationId());
        assertThat(invitations.findById(invitedOnly.id()).orElseThrow().status())
            .isEqualTo(InvitationStatus.EXPIRED);
        assertThat(events.published).anySatisfy(event ->
            assertThat(event).isInstanceOf(TournamentStartedEvent.class));
    }

    @Test
    @DisplayName("недостаток участников: CANCELLED + INSUFFICIENT_PARTICIPANTS, резервы освобождены")
    void старт_недостаток() {
        UUID tournamentId = openTournament(NOW.plusSeconds(1));
        Invitation ready = readyInvitation(tournamentId);

        service.startDue(NOW.plusSeconds(2), 10);

        var tournament = tournaments.findById(tournamentId).orElseThrow();
        assertThat(tournament.status()).isEqualTo(TournamentStatus.CANCELLED);
        assertThat(tournament.cancelReason().name()).isEqualTo("INSUFFICIENT_PARTICIPANTS");
        assertThat(eligibility.isReleased(ready.reservationId())).isTrue();
        assertThat(entries.findByTournamentId(tournamentId, 0, 50)).isEmpty();
        assertThat(events.published).noneSatisfy(event ->
            assertThat(event).isInstanceOf(TournamentStartedEvent.class));
    }

    @Test
    @DisplayName("ручка: до дедлайна 409; после — RUNNING; повтор — 409 (идемпотентность scheduler'а — no-op)")
    void ручка_старта() {
        UUID tournamentId = openTournament(NOW.plusSeconds(3600));
        readyInvitation(tournamentId);
        readyInvitation(tournamentId);

        assertThatThrownBy(() -> service.start(organizer, tournamentId))
            .isInstanceOf(TournamentStateConflictException.class); // до дедлайна

        StartTournamentService afterDeadline = new StartTournamentService(tournaments,
            invitations, entries, windows, eligibility, accessPolicy, events,
            Clock.fixed(NOW.plusSeconds(7200), ZoneOffset.UTC), transactionTemplate());
        assertThat(afterDeadline.start(organizer, tournamentId).status()).isEqualTo("RUNNING");

        assertThatThrownBy(() -> afterDeadline.start(organizer, tournamentId))
            .isInstanceOf(TournamentStateConflictException.class); // уже RUNNING
    }

    @Test
    @DisplayName("startDue: только due (deadline <= now); недедлайнные не тронуты; повтор — no-op")
    void startDue_фильтр_и_идемпотентность() {
        UUID dueTournament = openTournament(NOW.plusSeconds(1));
        readyInvitation(dueTournament);
        readyInvitation(dueTournament);
        UUID futureTournament = openTournament(NOW.plusSeconds(3600));
        readyInvitation(futureTournament);
        readyInvitation(futureTournament);

        assertThat(service.startDue(NOW.plusSeconds(2), 10)).isEqualTo(1);
        assertThat(tournaments.findById(futureTournament).orElseThrow().status())
            .isEqualTo(TournamentStatus.REGISTRATION_OPEN);

        assertThat(service.startDue(NOW.plusSeconds(3), 10)).isZero(); // уже обработан
    }

    @Test
    @DisplayName("старт создаёт первый VotingWindow: sequence 1, OPEN, состав = допущенные, счёт 0")
    void старт_создаёт_первое_окно() {
        UUID tournamentId = openTournament(NOW.plusSeconds(1));
        readyInvitation(tournamentId);
        readyInvitation(tournamentId);

        service.startDue(NOW.plusSeconds(2), 10);

        VotingWindow window = windows.findLatestByTournamentId(tournamentId).orElseThrow();
        assertThat(window.sequence()).isEqualTo(1);
        assertThat(window.status()).isEqualTo(WindowStatus.OPEN);
        assertThat(window.opensAt()).isEqualTo(NOW.plusSeconds(2)); // now старта
        assertThat(window.closesAt()).isEqualTo(window.opensAt().plusSeconds(3600));
        assertThat(window.participants()).hasSize(2);
        assertThat(window.participants()).allSatisfy(p -> assertThat(p.score()).isZero());
    }

    @Test
    @DisplayName("старт публикует VotingWindowOpened с составом первого окна (проекция feed)")
    void старт_публикует_открытие_окна() {
        UUID tournamentId = openTournament(NOW.plusSeconds(1));
        Invitation ready1 = readyInvitation(tournamentId);
        Invitation ready2 = readyInvitation(tournamentId);

        service.startDue(NOW.plusSeconds(2), 10);

        VotingWindowOpenedEvent opened = events.published.stream()
            .filter(VotingWindowOpenedEvent.class::isInstance)
            .map(VotingWindowOpenedEvent.class::cast)
            .findFirst().orElseThrow();
        assertThat(opened.payload().scope()).isEqualTo("PRIVATE");
        assertThat(opened.payload().sequence()).isEqualTo(1);
        assertThat(opened.payload().participants()).hasSize(2);
        assertThat(opened.payload().participants())
            .extracting(VotingWindowOpenedEvent.Participant::userId)
            .containsExactlyInAnyOrder(ready1.userId(), ready2.userId());
        assertThat(opened.payload().participants())
            .allSatisfy(p -> assertThat(p.plantId()).isNotNull());
    }
}
