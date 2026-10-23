package com.plantarena.plants.adapter.out.events;

import com.plantarena.plants.api.client.TournamentsEventsClient;
import com.plantarena.plants.api.event.PlantDiedEvent;
import com.plantarena.plants.api.event.PlantModerationDecidedEvent;
import com.plantarena.plants.api.event.PlantSubmittedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class TournamentsEventRelay {

    private final TournamentsEventsClient tournamentsEventsClient;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onModerationDecided(PlantModerationDecidedEvent event) {
        try {
            tournamentsEventsClient.plantModerationDecided(
                new TournamentsEventsClient.PlantModerationDecidedPayload(
                    event.payload().plantId(), event.payload().decision()));
        } catch (Exception e) {
            log.warn("Не доставлено событие PlantModerationDecided={} в tournaments-service: {}",
                event.payload().plantId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(PlantSubmittedEvent event) {
        log.debug("PlantSubmitted={} получен moderation-сервисом через polling", event.payload().plantId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDied(PlantDiedEvent event) {
        log.debug("PlantDied={} получателей нет (lab2)", event.payload().plantId());
    }
}
