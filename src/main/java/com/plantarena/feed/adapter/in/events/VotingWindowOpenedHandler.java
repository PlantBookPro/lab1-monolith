package com.plantarena.feed.adapter.in.events;

import com.plantarena.feed.application.port.in.ProjectWindowUseCase;
import com.plantarena.tournaments.api.event.VotingWindowOpenedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Подписка на открытие окна (раздел 9): перевод опубликованного события в
 * команду проекции. Синхронно в tx издателя — карточки появляются вместе
 * с окном (обязательное последствие, раздел 10.3).
 */
@Component
public class VotingWindowOpenedHandler {

    private final ProjectWindowUseCase projection;

    public VotingWindowOpenedHandler(ProjectWindowUseCase projection) {
        this.projection = projection;
    }

    @EventListener
    public void on(VotingWindowOpenedEvent event) {
        projection.onWindowOpened(new ProjectWindowUseCase.WindowCardsCommand(
            event.payload().windowId(), event.payload().tournamentId(),
            event.payload().scope(), event.payload().clusterId(),
            event.payload().closesAt(),
            event.payload().participants().stream()
                .map(p -> new ProjectWindowUseCase.CardSeed(p.entryId(), p.userId(),
                    p.plantId(), p.joinedAt()))
                .toList()));
    }
}
