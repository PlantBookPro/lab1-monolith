package com.plantarena.tournaments.application;

import com.plantarena.shared.event.IntegrationEventPublisher;
import com.plantarena.tournaments.api.event.EntryEliminatedEvent;
import com.plantarena.tournaments.api.event.TournamentFinishedEvent;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.application.port.out.PlantLifecycleGateway;
import com.plantarena.tournaments.application.support.FakeEventPublisher;
import com.plantarena.tournaments.application.support.FakePlantEligibilityGateway;
import com.plantarena.tournaments.application.support.FakePlantLifecycleGateway;
import com.plantarena.tournaments.application.support.InMemoryInvitationRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentRepository;
import com.plantarena.tournaments.application.support.InMemoryVotingWindowRepository;
import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentStatus;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingSubject;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.WindowStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Закрытие окон (разделы 7, 12.3): выбывание, гибель, резервы, следующий раунд. */
@DisplayName("Закрытие окна: идемпотентно, с гибелью и следующим раундом")
class CloseVotingWindowServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    private final InMemoryTournamentRepository tournaments = new InMemoryTournamentRepository();
    private final InMemoryInvitationRepository invitations = new InMemoryInvitationRepository();
    private final InMemoryTournamentEntryRepository entries =
        new InMemoryTournamentEntryRepository();
    private final InMemoryVotingWindowRepository windows = new InMemoryVotingWindowRepository();
    private final FakePlantEligibilityGateway eligibility = new FakePlantEligibilityGateway();
    private final FakePlantLifecycleGateway plantLifecycle = new FakePlantLifecycleGateway();
    private final FakeEventPublisher eventPublisher = new FakeEventPublisher();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private UUID tournamentId;
    private UUID entry1;
    private UUID entry2;
    private UUID entry3;
    private UUID plant1;
    private UUID plant2;
    private UUID plant3;
    private UUID reservation1;
    private UUID reservation2;
    private UUID reservation3;
    private CloseVotingWindowService service;

    @BeforeEach
    void setUp() {
        UUID organizer = UUID.randomUUID();
        Instant deadline = NOW.minusSeconds(3600);
        Tournament tournament = Tournament.createDraft(organizer, "Турнир", null,
            deadline, Duration.ofSeconds(60), 0.5, 2, Set.of(), deadline.minusSeconds(600));
        tournament.openRegistration(deadline.minusSeconds(599));
        tournament.start(NOW, 3);
        tournaments.save(tournament);
        tournamentId = tournament.id();

        entry1 = admit(UUID.randomUUID(), reservation1 = UUID.randomUUID(), plant1 = UUID.randomUUID());
        entry2 = admit(UUID.randomUUID(), reservation2 = UUID.randomUUID(), plant2 = UUID.randomUUID());
        entry3 = admit(UUID.randomUUID(), reservation3 = UUID.randomUUID(), plant3 = UUID.randomUUID());

        service = new CloseVotingWindowService(windows, tournaments, entries, eligibility,
            plantLifecycle, eventPublisher, clock, txTemplate());
    }

    @Test
    @DisplayName("закрытие due-окна: худший выбывает, гибель PERMANENT, резерв освобождён, следующий раунд")
    void закрытие_с_выбыванием() {
        VotingWindow window = openDueWindow();
        window.castVote(VotingSubject.user(UUID.randomUUID()), entry2, VoteValue.LIKE,
            NOW.minusSeconds(100));
        windows.save(window);
        // счёт: entry2 +1, entry3 0, entry1 −1... голосуем против entry1:
        window.castVote(VotingSubject.user(UUID.randomUUID()), entry1, VoteValue.DISLIKE,
            NOW.minusSeconds(100));
        windows.save(window);

        int processed = service.closeDue(NOW, 10);

        assertThat(processed).isEqualTo(1);
        assertThat(statusOf(entry1)).isEqualTo(EntryStatus.ELIMINATED);
        assertThat(statusOf(entry2)).isEqualTo(EntryStatus.ACTIVE);
        assertThat(plantLifecycle.isDead(plant1)).isTrue();
        assertThat(plantLifecycle.calls).singleElement()
            .satisfies(call -> {
                assertThat(call.kind()).isEqualTo(PlantLifecycleGateway.RestrictionKind.PERMANENT);
                assertThat(call.cooldownExpiresAt()).isNull();
                assertThat(call.sourceEntryId()).isEqualTo(entry1);
            });
        assertThat(eligibility.isReleased(reservation1)).isTrue();
        assertThat(eligibility.isReleased(reservation2)).isFalse();
        assertThat(eventPublisher.published).anySatisfy(event ->
            assertThat(event).isInstanceOf(EntryEliminatedEvent.class));

        // следующий раунд: sequence 2, выжившие, счёт с нуля, OPEN
        VotingWindow next = windows.findLatestByTournamentId(tournamentId).orElseThrow();
        assertThat(next.sequence()).isEqualTo(2);
        assertThat(next.status()).isEqualTo(WindowStatus.OPEN);
        assertThat(next.hasEntry(entry1)).isFalse();
        assertThat(next.hasEntry(entry2)).isTrue();
        assertThat(next.scoreOf(entry2)).isZero();
        assertThat(tournaments.findById(tournamentId).orElseThrow().status())
            .isEqualTo(TournamentStatus.RUNNING);
    }

    @Test
    @DisplayName("один выживший — WINNER, турнир FINISHED, резерв победителя освобождён")
    void победитель() {
        // окно с двумя участниками: entry2 LIKE от субъекта, entry1 без голосов
        VotingWindow window = VotingWindow.open(tournamentId, 1, List.of(
            new VotingWindow.ParticipantSeed(entry1, UUID.randomUUID(), NOW),
            new VotingWindow.ParticipantSeed(entry2, UUID.randomUUID(), NOW)),
            NOW.minusSeconds(120), NOW.minusSeconds(60), NOW.minusSeconds(120));
        window.castVote(VotingSubject.user(UUID.randomUUID()), entry2, VoteValue.LIKE,
            NOW.minusSeconds(100));
        windows.save(window);

        service.closeDue(NOW, 10);

        assertThat(statusOf(entry1)).isEqualTo(EntryStatus.ELIMINATED);
        assertThat(statusOf(entry2)).isEqualTo(EntryStatus.WINNER);
        assertThat(tournaments.findById(tournamentId).orElseThrow().status())
            .isEqualTo(TournamentStatus.FINISHED);
        assertThat(eligibility.isReleased(reservation2)).isTrue();
        assertThat(eventPublisher.published).anySatisfy(event ->
            assertThat(event).isInstanceOf(TournamentFinishedEvent.class));
        assertThat(windows.countByTournamentId(tournamentId)).isEqualTo(1); // нового окна нет
    }

    @Test
    @DisplayName("повторное закрытие того же окна — no-op: без повторной гибели и без дубля раунда")
    void повторное_закрытие_идемпотентно() {
        openDueWindow();
        service.closeDue(NOW, 10);
        int deathCount = plantLifecycle.calls.size();
        int windowCount = (int) windows.countByTournamentId(tournamentId);

        int processed = service.closeDue(NOW, 10);

        assertThat(processed).isZero();
        assertThat(plantLifecycle.calls).hasSize(deathCount);
        assertThat(windows.countByTournamentId(tournamentId)).isEqualTo(windowCount);
    }

    @Test
    @DisplayName("не-due окно не закрывается (closesAt в будущем)")
    void не_due_окно() {
        windows.save(VotingWindow.open(tournamentId, 1, seeds(), NOW,
            NOW.plusSeconds(60), NOW));
        assertThat(service.closeDue(NOW, 10)).isZero();
        assertThat(windows.findLatestByTournamentId(tournamentId).orElseThrow().status())
            .isEqualTo(WindowStatus.OPEN);
    }

    private VotingWindow openDueWindow() {
        VotingWindow window = VotingWindow.open(tournamentId, 1, seeds(),
            NOW.minusSeconds(120), NOW.minusSeconds(60), NOW.minusSeconds(120));
        windows.save(window);
        return window;
    }

    private UUID admit(UUID userId, UUID reservationId, UUID plantId) {
        return entries.save(TournamentEntry.admit(tournamentId, userId, plantId,
            reservationId, NOW)).id();
    }

    private EntryStatus statusOf(UUID entryId) {
        return entries.entries.get(entryId).status();
    }

    private List<VotingWindow.ParticipantSeed> seeds() {
        return List.of(
            new VotingWindow.ParticipantSeed(entry1, UUID.randomUUID(), NOW),
            new VotingWindow.ParticipantSeed(entry2, UUID.randomUUID(), NOW),
            new VotingWindow.ParticipantSeed(entry3, UUID.randomUUID(), NOW));
    }

    /** TransactionTemplate без менеджера транзакций: выполняет действие сразу (unit). */
    private org.springframework.transaction.support.TransactionTemplate txTemplate() {
        return new org.springframework.transaction.support.TransactionTemplate() {
            @Override
            public void executeWithoutResult(java.util.function.Consumer<
                    org.springframework.transaction.TransactionStatus> action) {
                action.accept(new org.springframework.transaction.support.SimpleTransactionStatus());
            }
        };
    }
}
