package com.plantarena.feed.application;

import com.plantarena.feed.application.port.in.ProjectWindowUseCase;
import com.plantarena.feed.application.port.out.OwnerDirectory;
import com.plantarena.feed.application.port.out.PlantCatalog;
import com.plantarena.feed.domain.FeedCard;
import com.plantarena.feed.domain.FeedCardRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Проекция ленты (ADR-002): событие открытия окна → карточки (обогащение
 * title/assetId из plants, displayName из identity — снимок на момент
 * открытия); закрытие окна → удаление карточек. Вызывается синхронным
 * @EventListener в tx издателя (как PlantModerationDecidedHandler).
 */
@Service
public class FeedProjectionService implements ProjectWindowUseCase {

    private static final Logger log = LoggerFactory.getLogger(FeedProjectionService.class);

    private final FeedCardRepository cards;
    private final PlantCatalog plantCatalog;
    private final OwnerDirectory ownerDirectory;
    private final Clock clock;

    public FeedProjectionService(FeedCardRepository cards, PlantCatalog plantCatalog,
                                 OwnerDirectory ownerDirectory, Clock clock) {
        this.cards = cards;
        this.plantCatalog = plantCatalog;
        this.ownerDirectory = ownerDirectory;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void onWindowOpened(ProjectWindowUseCase.WindowCardsCommand command) {
        Instant now = clock.instant();
        List<FeedCard> newCards = new ArrayList<>(command.cards().size());
        for (ProjectWindowUseCase.CardSeed seed : command.cards()) {
            PlantCatalog.PlantView plant = plantCatalog.findPlant(seed.plantId()).orElse(null);
            if (plant == null || !"ALIVE".equals(plant.lifeStatus())) {
                log.warn("Карточка {} пропущена: растение {} отсутствует или не живое",
                    seed.entryId(), seed.plantId());
                continue;
            }
            String displayName = ownerDirectory.findOwner(seed.userId())
                .map(OwnerDirectory.OwnerView::displayName)
                .orElse("Участник");
            newCards.add(new FeedCard(UUID.randomUUID(), command.windowId(),
                command.tournamentId(), command.scope(), command.clusterId(), seed.entryId(),
                seed.userId(), seed.plantId(), plant.assetId(), plant.title(), displayName,
                seed.joinedAt(), command.closesAt(), now, 0L));
        }
        if (!newCards.isEmpty()) {
            cards.saveAll(newCards);
        }
    }

    @Override
    @Transactional
    public void onWindowClosed(UUID windowId) {
        cards.deleteAllByWindowId(windowId);
    }
}
