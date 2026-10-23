package com.plantarena.tournaments.adapter.out.events;

import com.plantarena.tournaments.api.client.FeedEventsClient;
import com.plantarena.tournaments.api.event.EntryEliminatedEvent;
import com.plantarena.tournaments.api.event.InvitationCreatedEvent;
import com.plantarena.tournaments.api.event.TournamentFinishedEvent;
import com.plantarena.tournaments.api.event.TournamentStartedEvent;
import com.plantarena.tournaments.api.event.VotingWindowClosedEvent;
import com.plantarena.tournaments.api.event.VotingWindowOpenedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class FeedProjectionRelay {

    private final FeedEventsClient feedEventsClient;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWindowOpened(VotingWindowOpenedEvent event) {
        VotingWindowOpenedEvent.Payload payload = event.payload();
        try {
            feedEventsClient.windowOpened(new FeedEventsClient.WindowProjectionRequest(
                payload.windowId(), payload.tournamentId(), payload.scope(), payload.clusterId(),
                payload.closesAt(),
                payload.participants().stream()
                    .map(p -> new FeedEventsClient.CardSeedView(p.entryId(), p.userId(),
                        p.plantId(), p.joinedAt()))
                    .toList()));
        } catch (Exception e) {
            log.warn("Прокция окна {} не доставлена в feed-service: {}", payload.windowId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWindowClosed(VotingWindowClosedEvent event) {
        try {
            feedEventsClient.windowClosed(event.payload().windowId());
        } catch (Exception e) {
            log.warn("Закрытие окна {} не доставлено в feed-service: {}", event.payload().windowId(),
                e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOther(TournamentStartedEvent e) { log.debug("TournamentStarted получателей нет (lab2)"); }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOther(TournamentFinishedEvent e) { log.debug("TournamentFinished получателей нет (lab2)"); }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOther(EntryEliminatedEvent e) { log.debug("EntryEliminated получателей нет (lab2)"); }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOther(InvitationCreatedEvent e) { log.debug("InvitationCreated получателей нет (lab2)"); }
}
