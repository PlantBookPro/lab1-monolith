package com.plantarena.moderation.adapter.in.events;

import com.plantarena.moderation.application.port.in.CreateModerationJobUseCase;
import com.plantarena.plants.api.event.PlantSubmittedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Подписка на PlantSubmitted (in-process Spring-событие, конверт
 * IntegrationEvent): moderation — downstream plants (раздел 4.3). Слушатель
 * живёт в adapter.in.events — ACL-место по context map (чужой контекст —
 * только его api). Синхронный вызов внутри транзакции подачи: задание
 * появляется атомарно с растением (раздел 12). В лабе №4 заменяется
 * Kafka-слушателем без изменения use case.
 */
@Component
public class PlantSubmittedHandler {

    private final CreateModerationJobUseCase createModerationJob;

    public PlantSubmittedHandler(CreateModerationJobUseCase createModerationJob) {
        this.createModerationJob = createModerationJob;
    }

    @EventListener
    public void onPlantSubmitted(PlantSubmittedEvent event) {
        createModerationJob.onPlantSubmitted(event.payload().plantId(),
            event.payload().assetId());
    }
}
