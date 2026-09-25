package com.plantarena.plants.application.support;

import com.plantarena.shared.event.IntegrationEvent;
import com.plantarena.shared.event.IntegrationEventPublisher;
import java.util.ArrayList;
import java.util.List;

/** Фейк публикации событий: собирает опубликованные события для проверок. */
public class FakeIntegrationEventPublisher implements IntegrationEventPublisher {

    public final List<IntegrationEvent> published = new ArrayList<>();

    @Override
    public void publish(IntegrationEvent event) {
        published.add(event);
    }
}
