package com.plantarena.feed.adapter.in.events;

import com.plantarena.feed.application.port.in.ProjectWindowUseCase;
import com.plantarena.tournaments.api.event.VotingWindowClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Подписка на закрытие окна (раздел 9): карточки окна удаляются. */
@Component
public class VotingWindowClosedHandler {

    private final ProjectWindowUseCase projection;

    public VotingWindowClosedHandler(ProjectWindowUseCase projection) {
        this.projection = projection;
    }

    @EventListener
    public void on(VotingWindowClosedEvent event) {
        projection.onWindowClosed(event.payload().windowId());
    }
}
