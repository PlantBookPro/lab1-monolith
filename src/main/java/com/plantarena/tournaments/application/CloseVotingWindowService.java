package com.plantarena.tournaments.application;

import com.plantarena.shared.event.IntegrationEventPublisher;
import com.plantarena.tournaments.api.event.EntryEliminatedEvent;
import com.plantarena.tournaments.api.event.TournamentFinishedEvent;
import com.plantarena.tournaments.application.port.in.CloseVotingWindowUseCase;
import com.plantarena.tournaments.application.port.out.PlantEligibilityGateway;
import com.plantarena.tournaments.application.port.out.PlantLifecycleGateway;
import com.plantarena.tournaments.domain.EliminationAlgorithms;
import com.plantarena.tournaments.domain.Tournament;
import com.plantarena.tournaments.domain.TournamentEntry;
import com.plantarena.tournaments.domain.TournamentEntryRepository;
import com.plantarena.tournaments.domain.TournamentRepository;
import com.plantarena.tournaments.domain.VotingWindow;
import com.plantarena.tournaments.domain.VotingWindowRepository;
import com.plantarena.tournaments.domain.WindowStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Один use case закрытия окон для scheduler'а и demo-ручки (раздел 12.3,
 * дизайн итерации 6): каждое окно — в отдельной короткой tx; один сбой не
 * блокирует остальные. Внутри tx (ADR-011): блокировка окна → повтор для
 * CLOSED — no-op → закрытие доменом (рейтинг + выбывание) → entry
 * ELIMINATED/WINNER → команды plants (гибель PERMANENT, освобождение
 * резервов; растения в устойчивом порядке по plantId) → следующий раунд или
 * FINISHED + TournamentFinished. События EntryEliminated/TournamentFinished
 * публикуются в той же tx.
 */
@Service
public class CloseVotingWindowService implements CloseVotingWindowUseCase {

    private static final Logger log = LoggerFactory.getLogger(CloseVotingWindowService.class);

    private final VotingWindowRepository windows;
    private final TournamentRepository tournaments;
    private final TournamentEntryRepository entries;
    private final PlantEligibilityGateway eligibility;
    private final PlantLifecycleGateway plantLifecycle;
    private final IntegrationEventPublisher eventPublisher;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public CloseVotingWindowService(VotingWindowRepository windows,
                                    TournamentRepository tournaments,
                                    TournamentEntryRepository entries,
                                    PlantEligibilityGateway eligibility,
                                    PlantLifecycleGateway plantLifecycle,
                                    IntegrationEventPublisher eventPublisher, Clock clock,
                                    TransactionTemplate transactionTemplate) {
        this.windows = windows;
        this.tournaments = tournaments;
        this.entries = entries;
        this.eligibility = eligibility;
        this.plantLifecycle = plantLifecycle;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int closeDue(Instant now, int limit) {
        int processed = 0;
        for (UUID windowId : windows.findDueForClose(now, limit)) {
            try {
                transactionTemplate.executeWithoutResult(
                    status -> closeOne(windowId, clock.instant()));
                processed++;
            } catch (RuntimeException e) {
                // одно окно не блокирует остальные; повтор — следующий poll
                log.warn("Закрытие окна {} не удалось, будет повторено: {}", windowId,
                    e.getMessage());
            }
        }
        return processed;
    }

    private void closeOne(UUID windowId, Instant now) {
        VotingWindow window = windows.findByIdForUpdate(windowId)
            .orElseThrow(() -> new WindowNotFoundException("Окно не найдено: " + windowId));
        if (window.status() != WindowStatus.OPEN || now.isBefore(window.closesAt())) {
            return; // уже закрыто (идемпотентность повтора) или ещё не due
        }
        Tournament tournament = findTournament(window.tournamentId());
        VotingWindow.CloseOutcome outcome = window.close(now,
            EliminationAlgorithms.forKind(tournament.algorithm()),
            tournament.eliminationFraction());
        windows.save(window);

        List<TournamentEntry> eliminated = outcome.eliminatedEntryIds().stream()
            .map(this::findEntry)
            .sorted(Comparator.comparing(TournamentEntry::plantId)) // устойчивый порядок (12.3)
            .toList();
        for (TournamentEntry entry : eliminated) {
            entry.eliminate();
            entries.save(entry);
            plantLifecycle.registerDeath(entry.plantId(),
                PlantLifecycleGateway.RestrictionKind.PERMANENT, null,
                "Поражение в раунде " + window.sequence() + " турнира «"
                    + tournament.name() + "»",
                entry.id());
            eligibility.release(entry.reservationId());
            publishEliminated(window, tournament, entry, now);
        }
        if (outcome.winnerEntryId() != null) {
            TournamentEntry winner = findEntry(outcome.winnerEntryId());
            winner.declareWinner();
            entries.save(winner);
            eligibility.release(winner.reservationId());
            tournament.finish(now);
            tournaments.save(tournament);
            publishFinished(tournament, winner, now);
        } else {
            windows.save(VotingWindow.open(tournament.id(), window.sequence() + 1,
                seedsFor(outcome.survivedEntryIds()), now,
                now.plus(tournament.roundDuration()), now));
        }
    }

    private List<VotingWindow.ParticipantSeed> seedsFor(List<UUID> entryIds) {
        return entryIds.stream()
            .map(entryId -> {
                TournamentEntry entry = findEntry(entryId);
                return new VotingWindow.ParticipantSeed(entry.id(), entry.userId(),
                    entry.joinedAt());
            })
            .toList();
    }

    private void publishEliminated(VotingWindow window, Tournament tournament,
                                   TournamentEntry entry, Instant now) {
        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new EntryEliminatedEvent(eventId, EntryEliminatedEvent.TYPE,
            EntryEliminatedEvent.SCHEMA_VERSION, window.id(), window.version(), now, eventId,
            new EntryEliminatedEvent.Payload(tournament.id(), entry.id(), entry.userId(),
                entry.plantId(), window.sequence())));
    }

    private void publishFinished(Tournament tournament, TournamentEntry winner, Instant now) {
        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new TournamentFinishedEvent(eventId, TournamentFinishedEvent.TYPE,
            TournamentFinishedEvent.SCHEMA_VERSION, tournament.id(), tournament.version(),
            now, eventId, new TournamentFinishedEvent.Payload(tournament.id(), winner.id(),
                winner.userId(), winner.plantId())));
    }

    private TournamentEntry findEntry(UUID entryId) {
        return entries.findById(entryId)
            .orElseThrow(() -> new IllegalStateException("Участие не найдено: " + entryId));
    }

    private Tournament findTournament(UUID tournamentId) {
        return tournaments.findById(tournamentId)
            .orElseThrow(() -> new TournamentNotFoundException(
                "Турнир не найден: " + tournamentId));
    }
}
