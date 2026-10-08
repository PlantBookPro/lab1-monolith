package com.plantarena.tournaments.application;

import com.plantarena.shared.event.IntegrationEventPublisher;
import com.plantarena.tournaments.api.event.EntryEliminatedEvent;
import com.plantarena.tournaments.api.event.TournamentFinishedEvent;
import com.plantarena.tournaments.api.event.VotingWindowClosedEvent;
import com.plantarena.tournaments.api.event.VotingWindowOpenedEvent;
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
import com.plantarena.tournaments.domain.WindowScope;
import com.plantarena.tournaments.domain.WindowStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;


@Service
public class CloseVotingWindowService implements CloseVotingWindowUseCase {

    private static final Logger log = LoggerFactory.getLogger(CloseVotingWindowService.class);

    private final VotingWindowRepository windows;
    private final TournamentRepository tournaments;
    private final TournamentEntryRepository entries;
    private final PlantEligibilityGateway eligibility;
    private final PlantLifecycleGateway plantLifecycle;
    private final IntegrationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public CloseVotingWindowService(VotingWindowRepository windows,
                                    TournamentRepository tournaments,
                                    TournamentEntryRepository entries,
                                    PlantEligibilityGateway eligibility,
                                    PlantLifecycleGateway plantLifecycle,
                                    IntegrationEventPublisher eventPublisher,
                                    TransactionTemplate transactionTemplate) {
        this.windows = windows;
        this.tournaments = tournaments;
        this.entries = entries;
        this.eligibility = eligibility;
        this.plantLifecycle = plantLifecycle;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int closeDue(Instant now, int limit) {
        int processed = 0;
        for (UUID windowId : windows.findDueForClose(now, limit)) {
            try {
                transactionTemplate.executeWithoutResult(
                    status -> closeOne(windowId, now));
                processed++;
            } catch (RuntimeException e) {
                
                log.warn("Закрытие окна {} не удалось, будет повторено: {}", windowId,
                    e.getMessage());
            }
        }
        return processed;
    }

    private void closeOne(UUID windowId, Instant now) {
        VotingWindow window = windows.findByIdForUpdate(windowId)
            .orElseThrow(() -> new WindowNotFoundException("Окно не найдено: " + windowId));
        if (window.scope() != WindowScope.PRIVATE) {
            return; 
        }
        if (window.status() != WindowStatus.OPEN || now.isBefore(window.closesAt())) {
            return; 
        }
        Tournament tournament = findTournament(window.tournamentId());
        VotingWindow.CloseOutcome outcome = window.close(now,
            EliminationAlgorithms.forKind(tournament.algorithm()),
            tournament.eliminationFraction());
        windows.save(window);
        publishClosed(window, now);

        List<TournamentEntry> eliminated = outcome.eliminatedEntryIds().stream()
            .map(this::findEntry)
            .sorted(Comparator.comparing(TournamentEntry::plantId)) 
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
            List<TournamentEntry> survived = outcome.survivedEntryIds().stream()
                .map(this::findEntry)
                .toList();
            VotingWindow next = VotingWindow.open(tournament.id(), window.sequence() + 1,
                survived.stream()
                    .map(entry -> new VotingWindow.ParticipantSeed(entry.id(), entry.userId(),
                        entry.joinedAt()))
                    .toList(),
                now, now.plus(tournament.roundDuration()), now);
            windows.save(next);
            publishOpened(next, survived.stream()
                .map(entry -> new VotingWindowOpenedEvent.Participant(entry.id(), entry.userId(),
                    entry.plantId(), entry.joinedAt()))
                .toList(), now);
        }
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

    
    private void publishOpened(VotingWindow window,
                               List<VotingWindowOpenedEvent.Participant> participants,
                               Instant now) {
        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new VotingWindowOpenedEvent(eventId,
            VotingWindowOpenedEvent.TYPE, VotingWindowOpenedEvent.SCHEMA_VERSION,
            window.id(), window.version(), now, eventId,
            new VotingWindowOpenedEvent.Payload(window.id(), window.tournamentId(),
                window.scope().name(), window.sequence(), window.epochId(), window.clusterId(),
                window.clusterKey(), window.opensAt(), window.closesAt(), participants)));
    }

    
    private void publishClosed(VotingWindow window, Instant now) {
        UUID eventId = UUID.randomUUID();
        eventPublisher.publish(new VotingWindowClosedEvent(eventId,
            VotingWindowClosedEvent.TYPE, VotingWindowClosedEvent.SCHEMA_VERSION,
            window.id(), window.version(), now, eventId,
            new VotingWindowClosedEvent.Payload(window.id(), window.tournamentId(),
                window.scope().name(), window.sequence())));
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
