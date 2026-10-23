package com.plantarena.tournaments.application;

import com.plantarena.tournaments.api.event.VotingWindowClosedEvent;
import com.plantarena.tournaments.api.event.VotingWindowOpenedEvent;
import com.plantarena.tournaments.application.port.in.AdvanceGlobalCompetitionUseCase;
import com.plantarena.tournaments.application.port.out.ParticipantLocationsGateway.UserLocation;
import com.plantarena.tournaments.application.port.out.PlantLifecycleGateway;
import com.plantarena.tournaments.application.support.FakeClusteringGateway;
import com.plantarena.tournaments.application.support.FakeEventPublisher;
import com.plantarena.tournaments.application.support.FakeParticipantLocationsGateway;
import com.plantarena.tournaments.application.support.FakePlantEligibilityGateway;
import com.plantarena.tournaments.application.support.FakePlantLifecycleGateway;
import com.plantarena.tournaments.application.support.InMemoryQualificationEpochRepository;
import com.plantarena.tournaments.application.support.InMemoryTournamentEntryRepository;
import com.plantarena.tournaments.application.support.InMemoryVotingWindowRepository;
import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.EpochStatus;
import com.plantarena.tournaments.domain.QualificationEpoch;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.VoteValue;
import com.plantarena.tournaments.domain.VotingSubject;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.WindowScope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Продвижение границ (раздел 8, алгоритмы 2–8): фиксированный порядок
 * одновременных границ и идемпотентность повтора.
 */
@DisplayName("AdvanceGlobalCompetitionService: порядок границ и идемпотентность")
class AdvanceGlobalCompetitionServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-27T10:00:00Z");
    private static final Instant T1 = T0.plus(Duration.ofHours(25)); // все дедлайны прошли

    private final InMemoryVotingWindowRepository windows = new InMemoryVotingWindowRepository();
    private final InMemoryQualificationEpochRepository epochs =
        new InMemoryQualificationEpochRepository();
    private final InMemoryTournamentEntryRepository entries = new InMemoryTournamentEntryRepository();
    private final FakeParticipantLocationsGateway locations = new FakeParticipantLocationsGateway();
    private final FakeClusteringGateway clustering = new FakeClusteringGateway();
    private final FakePlantEligibilityGateway eligibility = new FakePlantEligibilityGateway();
    private final FakePlantLifecycleGateway plantLifecycle = new FakePlantLifecycleGateway();
    private final FakeEventPublisher eventPublisher = new FakeEventPublisher();

    private final AdvanceGlobalCompetitionService service = new AdvanceGlobalCompetitionService(
        windows, epochs, entries, eligibility, plantLifecycle, locations, clustering,
        new GlobalCompetitionSettings(Duration.ofHours(24), Duration.ofHours(6)),
        eventPublisher, txTemplate());

    @Test
    @DisplayName("одновременные границы: квалификация закрыта → финал закрыт → новый финал с продвинутым → новая эпоха")
    void порядок_одновременных_границ() {
        // эпоха 1: кластер с двумя участиями (u1 победит по голосу), дедлайн прошёл
        UUID epochId = UUID.randomUUID();
        UUID clusterId = UUID.randomUUID();
        UUID loser = globalEntry(EntryStatus.QUALIFYING);
        UUID winner = globalEntry(EntryStatus.QUALIFYING);
        UUID promoted = globalEntry(EntryStatus.QUALIFYING);
        epochs.save(QualificationEpoch.open(epochId, GlobalCompetitionId.VALUE, 1,
            T0.minus(Duration.ofHours(25)), T0.minus(Duration.ofHours(1)), T0));
        VotingWindow qualification = VotingWindow.openQualification(GlobalCompetitionId.VALUE,
            epochId, clusterId, "u4pu", 1, List.of(seed(winner), seed(loser)),
            T0.minus(Duration.ofHours(25)), T0.minus(Duration.ofHours(1)), T0);
        qualification.castVote(VotingSubject.user(UUID.randomUUID()), winner, VoteValue.LIKE,
            T0.minus(Duration.ofHours(2))); // детерминированный top-1
        windows.save(qualification);
        windows.save(VotingWindow.openQualification(GlobalCompetitionId.VALUE, epochId,
            UUID.randomUUID(), "u8t", 1, List.of(seed(promoted)),
            T0.minus(Duration.ofHours(25)), T0.minus(Duration.ofHours(1)), T0));
        // финал 1: один выживший (n=1 — лидер), дедлайн прошёл
        UUID survivor = globalEntry(EntryStatus.FINALIST);
        windows.save(VotingWindow.openFinal(GlobalCompetitionId.VALUE, 1,
            List.of(seed(survivor)),
            T0.minus(Duration.ofHours(7)), T0.minus(Duration.ofHours(1)), T0));
        // очередь: новая заявка с координатами
        UUID queued = globalEntry(EntryStatus.QUEUED);
        TournamentEntry queuedEntry = entries.findById(queued).orElseThrow();
        locations.locations.put(queuedEntry.userId(),
            new UserLocation(55.7558, 37.6173, 1L));

        AdvanceGlobalCompetitionUseCase.AdvanceReport report = service.advance(T1);

        // (1) квалификация закрыта: top-1 продвинут (после шага (3) — FINALIST),
        // остальные гибнут с COOLDOWN
        assertThat(report.qualificationClosed()).isEqualTo(2);
        assertThat(entries.findById(loser).orElseThrow().status())
            .isEqualTo(EntryStatus.ELIMINATED);
        assertThat(epochs.findById(epochId).orElseThrow().status())
            .isEqualTo(EpochStatus.CLOSED);
        // гибель + COOLDOWN 24 ч + освобождение резерва (алгоритм 3, допущение 2)
        assertThat(plantLifecycle.calls).anySatisfy(call -> {
            assertThat(call.kind()).isEqualTo(PlantLifecycleGateway.RestrictionKind.COOLDOWN);
            assertThat(call.cooldownExpiresAt()).isEqualTo(T1.plus(Duration.ofHours(24)));
        });
        assertThat(eligibility.released)
            .contains(entries.findById(loser).orElseThrow().reservationId());
        // (2) старый финал закрыт: лидер остался FINALIST (алгоритм 7)
        assertThat(report.finalClosed()).isEqualTo(1);
        assertThat(entries.findById(survivor).orElseThrow().status())
            .isEqualTo(EntryStatus.FINALIST);
        // (3) новый финал: выживший + продвинутые (алгоритм 6)
        assertThat(report.finalsOpened()).isEqualTo(1);
        assertThat(entries.findById(winner).orElseThrow().status())
            .isEqualTo(EntryStatus.FINALIST);
        assertThat(windows.findOpenByScope(GlobalCompetitionId.VALUE, WindowScope.FINAL))
            .map(VotingWindow::sequence).contains(2);
        // (4) новая эпоха: заявка из очереди кластеризована (алгоритм 2)
        assertThat(report.epochsOpened()).isEqualTo(1);
        assertThat(clustering.called).isTrue();
        assertThat(entries.findById(queued).orElseThrow().status())
            .isEqualTo(EntryStatus.QUALIFYING);
        assertThat(epochs.findOpenByTournamentId(GlobalCompetitionId.VALUE)).isPresent();

        // события проекции ленты (раздел 9): закрытия квалификаций/финала, открытия финала/эпохи
        List<VotingWindowClosedEvent> closedEvents = eventPublisher.published.stream()
            .filter(VotingWindowClosedEvent.class::isInstance)
            .map(VotingWindowClosedEvent.class::cast)
            .toList();
        assertThat(closedEvents).extracting(event -> event.payload().scope())
            .containsExactlyInAnyOrder("QUALIFICATION", "QUALIFICATION", "FINAL");
        assertThat(closedEvents).extracting(event -> event.payload().windowId())
            .contains(qualification.id());

        VotingWindowOpenedEvent finalOpened = eventPublisher.published.stream()
            .filter(VotingWindowOpenedEvent.class::isInstance)
            .map(VotingWindowOpenedEvent.class::cast)
            .filter(event -> "FINAL".equals(event.payload().scope()))
            .findFirst().orElseThrow();
        assertThat(finalOpened.payload().sequence()).isEqualTo(2);
        assertThat(finalOpened.payload().participants())
            .extracting(VotingWindowOpenedEvent.Participant::entryId)
            .containsExactlyInAnyOrder(survivor, winner, promoted);

        VotingWindowOpenedEvent qualificationOpened = eventPublisher.published.stream()
            .filter(VotingWindowOpenedEvent.class::isInstance)
            .map(VotingWindowOpenedEvent.class::cast)
            .filter(event -> "QUALIFICATION".equals(event.payload().scope()))
            .findFirst().orElseThrow();
        assertThat(qualificationOpened.payload().clusterId()).isNotNull();
        assertThat(qualificationOpened.payload().clusterKey()).isEqualTo("fake-55.7558-37.6173");
        assertThat(qualificationOpened.payload().participants())
            .extracting(VotingWindowOpenedEvent.Participant::entryId)
            .containsExactly(queued);
        assertThat(qualificationOpened.payload().participants())
            .allSatisfy(p -> assertThat(p.plantId()).isNotNull());
    }

    @Test
    @DisplayName("повторный advance — no-op: ничего не закрывается и не открывается повторно")
    void идемпотентность_повтора() {
        UUID epochId = UUID.randomUUID();
        UUID entryId = globalEntry(EntryStatus.QUALIFYING);
        epochs.save(QualificationEpoch.open(epochId, GlobalCompetitionId.VALUE, 1,
            T0.minus(Duration.ofHours(25)), T0.minus(Duration.ofHours(1)), T0));
        windows.save(VotingWindow.openQualification(GlobalCompetitionId.VALUE, epochId,
            UUID.randomUUID(), "u4pu", 1, List.of(seed(entryId)),
            T0.minus(Duration.ofHours(25)), T0.minus(Duration.ofHours(1)), T0));
        service.advance(T1);

        AdvanceGlobalCompetitionUseCase.AdvanceReport second = service.advance(T1);

        assertThat(second.qualificationClosed()).isZero();
        assertThat(second.finalClosed()).isZero();
        assertThat(second.finalsOpened()).isZero(); // финал с одним участником уже открыт
        assertThat(windows.findOpenByScope(GlobalCompetitionId.VALUE, WindowScope.FINAL))
            .map(VotingWindow::sequence).contains(1);
        assertThat(second.epochsOpened()).isZero(); // очередь пуста
        assertThat(plantLifecycle.calls).isEmpty(); // никто не погиб повторно
    }

    @Test
    @DisplayName("эпоха не открывается без QUEUED-заявок (пустые эпохи не создаются)")
    void без_очереди_эпоха_не_открывается() {
        AdvanceGlobalCompetitionUseCase.AdvanceReport report = service.advance(T1);
        assertThat(report.epochsOpened()).isZero();
        assertThat(clustering.called).isFalse();
    }

    private UUID globalEntry(EntryStatus status) {
        TournamentEntry entry = TournamentEntry.queueForGlobal(GlobalCompetitionId.VALUE,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), T0);
        while (entry.status() != status) {
            if (entry.status() == EntryStatus.QUEUED) {
                entry.startQualifying();
            } else if (entry.status() == EntryStatus.QUALIFYING) {
                entry.promoteToFinalPending();
            } else {
                entry.becomeFinalist();
            }
        }
        entries.save(entry);
        return entry.id();
    }

    private VotingWindow.ParticipantSeed seed(UUID entryId) {
        TournamentEntry entry = entries.findById(entryId).orElseThrow();
        return new VotingWindow.ParticipantSeed(entry.id(), entry.userId(), entry.joinedAt());
    }

    /** TransactionTemplate без менеджера транзакций: выполняет действие сразу (unit). */
    private org.springframework.transaction.support.TransactionTemplate txTemplate() {
        return new org.springframework.transaction.support.TransactionTemplate() {
            @Override
            public <T> T execute(
                org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(
                    new org.springframework.transaction.support.SimpleTransactionStatus());
            }

            @Override
            public void executeWithoutResult(java.util.function.Consumer<
                    org.springframework.transaction.TransactionStatus> action) {
                action.accept(new org.springframework.transaction.support.SimpleTransactionStatus());
            }
        };
    }
}
