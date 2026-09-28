package com.plantarena.tournaments.application;

import com.plantarena.shared.event.IntegrationEventPublisher;
import com.plantarena.tournaments.api.event.EntryEliminatedEvent;
import com.plantarena.tournaments.application.port.in.AdvanceGlobalCompetitionUseCase;
import com.plantarena.tournaments.application.port.out.ClusteringGateway;
import com.plantarena.tournaments.application.port.out.ParticipantLocationsGateway;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.application.port.out.PlantLifecycleGateway;
import com.plantarena.tournaments.domain.EntryStatus;
import com.plantarena.tournaments.domain.QualificationEpoch;
import com.plantarena.tournaments.domain.QualificationEpochRepository;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import com.plantarena.tournaments.domain.WindowScope;
import com.plantarena.tournaments.domain.WindowStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Продвижение границ глобального турнира (раздел 8, алгоритм 6) — один use
 * case для scheduler'а и demo-ручки. Фиксированный порядок: (1) закрыть
 * due-квалификацию, (2) закрыть due-финал, (3) открыть следующий финал,
 * (4) открыть следующую эпоху. Каждое окно/шаг — короткая tx (ADR-012);
 * идемпотентность по статусам: CLOSED-окно и существующие OPEN-эпоха/финал —
 * no-op, рестарт не убивает растения повторно (раздел 12.3).
 */
@Service
public class AdvanceGlobalCompetitionService implements AdvanceGlobalCompetitionUseCase {

    private static final Logger log =
        LoggerFactory.getLogger(AdvanceGlobalCompetitionService.class);
    private static final int BATCH_SIZE = 50;
    private static final Duration GLOBAL_COOLDOWN = Duration.ofHours(24);

    private final VotingWindowRepository windows;
    private final QualificationEpochRepository epochs;
    private final TournamentEntryRepository entries;
    private final PlantEligibilityGateway eligibility;
    private final PlantLifecycleGateway plantLifecycle;
    private final ParticipantLocationsGateway locations;
    private final ClusteringGateway clustering;
    private final GlobalCompetitionSettings settings;
    private final IntegrationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public AdvanceGlobalCompetitionService(VotingWindowRepository windows,
                                           QualificationEpochRepository epochs,
                                           TournamentEntryRepository entries,
                                           PlantEligibilityGateway eligibility,
                                           PlantLifecycleGateway plantLifecycle,
                                           ParticipantLocationsGateway locations,
                                           ClusteringGateway clustering,
                                           GlobalCompetitionSettings settings,
                                           IntegrationEventPublisher eventPublisher,
                                           TransactionTemplate transactionTemplate) {
        this.windows = windows;
        this.epochs = epochs;
        this.entries = entries;
        this.eligibility = eligibility;
        this.plantLifecycle = plantLifecycle;
        this.locations = locations;
        this.clustering = clustering;
        this.settings = settings;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public AdvanceReport advance(Instant now) {
        int qualificationClosed = closeDueQualification(now);
        int finalClosed = closeDueFinal(now);
        int finalsOpened = openNextFinal(now);
        int epochsOpened = openNextEpoch(now);
        return new AdvanceReport(qualificationClosed, finalClosed, finalsOpened, epochsOpened);
    }

    /** (1) Закрытие due-квалификационных окон (алгоритмы 3–5). */
    private int closeDueQualification(Instant now) {
        int closed = 0;
        for (UUID windowId
            : windows.findDueForCloseByScope(WindowScope.QUALIFICATION, now, BATCH_SIZE)) {
            try {
                transactionTemplate.executeWithoutResult(status ->
                    closeQualificationOne(windowId, now));
                closed++;
            } catch (RuntimeException e) {
                log.warn("Закрытие квалификации {} не удалось, будет повторено: {}",
                    windowId, e.getMessage());
            }
        }
        return closed;
    }

    private void closeQualificationOne(UUID windowId, Instant now) {
        VotingWindow window = windows.findByIdForUpdate(windowId)
            .orElseThrow(() -> new IllegalStateException("Окно не найдено: " + windowId));
        if (window.status() != WindowStatus.OPEN || now.isBefore(window.closesAt())) {
            return; // уже закрыто (идемпотентность) или ещё не due
        }
        VotingWindow.QualificationCloseOutcome outcome = window.closeQualification(now);
        windows.save(window);

        TournamentEntry promoted = findEntry(outcome.promotedEntryId());
        promoted.promoteToFinalPending();
        entries.save(promoted);

        for (TournamentEntry entry : eliminatedSorted(outcome.eliminatedEntryIds())) {
            entry.eliminateFromGlobal();
            entries.save(entry);
            registerGlobalDeath(entry, "Поражение в квалификации эпохи " + window.sequence(),
                now);
            publishEliminated(window, entry, now);
        }
        if (windows.countOpenByEpochId(window.epochId()) == 0) {
            QualificationEpoch epoch = epochs.findById(window.epochId())
                .orElseThrow(() -> new IllegalStateException(
                    "Эпоха не найдена: " + window.epochId()));
            epoch.close(now);
            epochs.save(epoch);
        }
    }

    /** (2) Закрытие due-финального окна (алгоритмы 7–8). */
    private int closeDueFinal(Instant now) {
        int closed = 0;
        for (UUID windowId : windows.findDueForCloseByScope(WindowScope.FINAL, now, BATCH_SIZE)) {
            try {
                transactionTemplate.executeWithoutResult(status -> closeFinalOne(windowId, now));
                closed++;
            } catch (RuntimeException e) {
                log.warn("Закрытие финала {} не удалось, будет повторено: {}",
                    windowId, e.getMessage());
            }
        }
        return closed;
    }

    private void closeFinalOne(UUID windowId, Instant now) {
        VotingWindow window = windows.findByIdForUpdate(windowId)
            .orElseThrow(() -> new IllegalStateException("Окно не найдено: " + windowId));
        if (window.status() != WindowStatus.OPEN || now.isBefore(window.closesAt())) {
            return;
        }
        VotingWindow.FinalCloseOutcome outcome = window.closeFinal(now);
        windows.save(window);
        for (TournamentEntry entry : eliminatedSorted(outcome.eliminatedEntryIds())) {
            entry.eliminateFromGlobal();
            entries.save(entry);
            registerGlobalDeath(entry, "Поражение в финальном окне " + window.sequence(), now);
            publishEliminated(window, entry, now);
        }
        // выжившие остаются FINALIST — переход в следующее окно (3)
    }

    /** (3) Следующее финальное окно: выжившие + все FINAL_PENDING (алгоритм 6). */
    private int openNextFinal(Instant now) {
        Boolean opened = transactionTemplate.execute(status -> {
            if (windows.findOpenByScope(GlobalCompetitionId.VALUE, WindowScope.FINAL)
                .isPresent()) {
                return false;
            }
            for (TournamentEntry pending
                : entries.findByTournamentIdAndStatus(GlobalCompetitionId.VALUE,
                    EntryStatus.FINAL_PENDING)) {
                pending.becomeFinalist();
                entries.save(pending);
            }
            List<TournamentEntry> finalists = entries.findByTournamentIdAndStatus(
                GlobalCompetitionId.VALUE, EntryStatus.FINALIST);
            if (finalists.isEmpty()) {
                return false; // n = 0: финал ждёт заявок (алгоритм 7)
            }
            int nextSequence = windows
                .findLatestByTournamentIdAndScope(GlobalCompetitionId.VALUE, WindowScope.FINAL)
                .map(VotingWindow::sequence).orElse(0) + 1;
            windows.save(VotingWindow.openFinal(GlobalCompetitionId.VALUE, nextSequence,
                seeds(finalists), now, now.plus(settings.finalWindowDuration()), now));
            return true;
        });
        return Boolean.TRUE.equals(opened) ? 1 : 0;
    }

    /** (4) Следующая эпоха: QUEUED → кластеризация → QUALIFYING + окна (алгоритм 2). */
    private int openNextEpoch(Instant now) {
        Boolean opened = transactionTemplate.execute(status -> {
            if (epochs.findOpenByTournamentId(GlobalCompetitionId.VALUE).isPresent()) {
                return false;
            }
            List<TournamentEntry> queued = entries.findByTournamentIdAndStatus(
                GlobalCompetitionId.VALUE, EntryStatus.QUEUED);
            if (queued.isEmpty()) {
                return false; // пустые эпохи не создаются (дизайн, решение 4)
            }
            List<ClusteringGateway.MemberLocation> members = new ArrayList<>();
            for (TournamentEntry entry : queued) {
                locations.findLocation(entry.userId()).ifPresent(location ->
                    members.add(new ClusteringGateway.MemberLocation(entry.id(),
                        entry.userId(), location.latitude(), location.longitude(),
                        location.locationVersion())));
            }
            if (members.isEmpty()) {
                return false;
            }
            UUID epochId = UUID.randomUUID();
            List<ClusteringGateway.AssignedCluster> clusters =
                clustering.assignClusters(epochId, members);
            int nextSequence = epochs.findLastByTournamentId(GlobalCompetitionId.VALUE)
                .map(QualificationEpoch::sequence).orElse(0) + 1;
            Instant closesAt = now.plus(settings.epochDuration());
            epochs.save(QualificationEpoch.open(epochId, GlobalCompetitionId.VALUE,
                nextSequence, now, closesAt, now));
            for (ClusteringGateway.AssignedCluster cluster : clusters) {
                List<TournamentEntry> clusterEntries = cluster.entryIds().stream()
                    .map(this::findEntry).toList();
                for (TournamentEntry entry : clusterEntries) {
                    entry.startQualifying();
                    entries.save(entry);
                }
                windows.save(VotingWindow.openQualification(GlobalCompetitionId.VALUE, epochId,
                    cluster.clusterId(), cluster.clusterKey(), nextSequence,
                    seeds(clusterEntries), now, closesAt, now));
            }
            return true;
        });
        return Boolean.TRUE.equals(opened) ? 1 : 0;
    }

    /** Гибель + суточный запрет совпавшей картинки + освобождение резерва. */
    private void registerGlobalDeath(TournamentEntry entry, String reason, Instant now) {
        plantLifecycle.registerDeath(entry.plantId(),
            PlantLifecycleGateway.RestrictionKind.COOLDOWN, now.plus(GLOBAL_COOLDOWN),
            reason, entry.id());
        eligibility.release(entry.reservationId());
    }

    private void publishEliminated(VotingWindow window, TournamentEntry entry, Instant now) {
        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new EntryEliminatedEvent(eventId, EntryEliminatedEvent.TYPE,
            EntryEliminatedEvent.SCHEMA_VERSION, window.id(), window.version(), now, eventId,
            new EntryEliminatedEvent.Payload(window.tournamentId(), entry.id(), entry.userId(),
                entry.plantId(), window.sequence())));
    }

    private List<TournamentEntry> eliminatedSorted(List<UUID> entryIds) {
        return entryIds.stream().map(this::findEntry)
            .sorted(Comparator.comparing(TournamentEntry::plantId)) // устойчивый порядок (12.3)
            .toList();
    }

    private List<VotingWindow.ParticipantSeed> seeds(List<TournamentEntry> finalists) {
        return finalists.stream()
            .map(entry -> new VotingWindow.ParticipantSeed(entry.id(), entry.userId(),
                entry.joinedAt()))
            .toList();
    }

    private TournamentEntry findEntry(UUID entryId) {
        return entries.findById(entryId)
            .orElseThrow(() -> new IllegalStateException("Участие не найдено: " + entryId));
    }
}
