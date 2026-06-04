package com.plantarena.tournaments.adapter.in.events;

import com.plantarena.plants.api.event.PlantModerationDecidedEvent;
import com.plantarena.tournaments.application.port.in.OnPlantModerationDecidedUseCase;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Подписка на PlantModerationDecided (in-process Spring-событие, конверт
 * IntegrationEvent): tournaments — downstream plants (раздел 4.3). Слушатель
 * живёт в adapter.in.events — ACL-место по context map. Синхронный вызов
 * внутри tx применения решения модерации: перевод заявки в READY/возврат в
 * INVITED атомарен с решением и DONE задания (раздел 12, ADR-010). В лабе
 * №4 заменяется Kafka-слушателем без изменения use case.
 */
@Component
public class PlantModerationDecidedHandler {

    private final OnPlantModerationDecidedUseCase onPlantModerationDecided;

    public PlantModerationDecidedHandler(OnPlantModerationDecidedUseCase onPlantModerationDecided) {
        this.onPlantModerationDecided = onPlantModerationDecided;
    }

    @EventListener
    public void onPlantModerationDecided(PlantModerationDecidedEvent event) {
        onPlantModerationDecided.onPlantModerationDecided(
            event.payload().plantId(), event.payload().decision());
    }
}
