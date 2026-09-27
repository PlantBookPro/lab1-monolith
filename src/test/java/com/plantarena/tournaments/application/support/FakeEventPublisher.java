package com.plantarena.tournaments.application.support;

import com.plantarena.shared.event.IntegrationEvent;
import com.plantarena.shared.event.IntegrationEventPublisher;
import java.util.ArrayList;
import java.util.List;

/** Фейк публикации событий: записи в списке. */
public class FakeEventPublisher implements IntegrationEventPublisher {

    public final List<IntegrationEvent> published = new ArrayList<>();

    @Override
    public void publish(IntegrationEvent event) {
        published.add(event);
    }
}
