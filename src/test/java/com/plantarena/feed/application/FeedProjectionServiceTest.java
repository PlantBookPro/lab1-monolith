package com.plantarena.feed.application;

import com.plantarena.feed.application.port.in.ProjectWindowUseCase;
import com.plantarena.feed.application.port.out.OwnerDirectory;
import com.plantarena.feed.application.port.out.PlantCatalog;
import com.plantarena.feed.application.support.InMemoryFeedCardRepository;
import com.plantarena.feed.domain.FeedCard;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проекция ленты (ADR-002): событие открытия окна пишет карточки с
 * обогащением из plants/identity; закрытие окна удаляет карточки.
 */
@DisplayName("FeedProjectionService: карточки по событиям окна")
class FeedProjectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private final InMemoryFeedCardRepository cards = new InMemoryFeedCardRepository();
    private final FeedProjectionService service = new FeedProjectionService(cards,
        new StubPlantCatalog(), new StubOwnerDirectory(),
        Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("открытие окна: карточки с title/assetId/displayName; неживое растение пропускается")
    void открытие_окна() {
        UUID aliveEntry = UUID.randomUUID();
        UUID deadEntry = UUID.randomUUID();
        UUID windowId = UUID.randomUUID();

        service.onWindowOpened(new ProjectWindowUseCase.WindowCardsCommand(windowId,
            UUID.randomUUID(), "QUALIFICATION", null, NOW.plusSeconds(600), List.of(
                new ProjectWindowUseCase.CardSeed(aliveEntry, UUID.randomUUID(),
                    UUID.nameUUIDFromBytes("plant-alive".getBytes()), NOW),
                new ProjectWindowUseCase.CardSeed(deadEntry, UUID.randomUUID(),
                    UUID.nameUUIDFromBytes("plant-dead".getBytes()), NOW))));

        List<FeedCard> saved = cards.page(new com.plantarena.feed.domain.FeedCardQuery(
            1L, NOW.plusSeconds(1), NOW, java.util.Set.of("QUALIFICATION"), java.util.Set.of(),
            null, java.util.Set.of(), null, null, 10));
        assertThat(saved).hasSize(1); // мёртвое растение не показывается
        assertThat(saved.get(0).entryId()).isEqualTo(aliveEntry);
        assertThat(saved.get(0).title()).isEqualTo("Живой фикус");
        assertThat(saved.get(0).ownerDisplayName()).isEqualTo("Владелец");
        assertThat(saved.get(0).closesAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    @DisplayName("закрытие окна: карточки окна удаляются (выжившие вернутся с новым окном)")
    void закрытие_окна() {
        UUID windowId = UUID.randomUUID();
        service.onWindowOpened(new ProjectWindowUseCase.WindowCardsCommand(windowId,
            UUID.randomUUID(), "FINAL", null, NOW.plusSeconds(600), List.of(
                new ProjectWindowUseCase.CardSeed(UUID.randomUUID(), UUID.randomUUID(),
                    UUID.nameUUIDFromBytes("plant-alive".getBytes()), NOW))));

        service.onWindowClosed(windowId);

        assertThat(cards.page(new com.plantarena.feed.domain.FeedCardQuery(
            1L, NOW.plusSeconds(1), NOW, java.util.Set.of("FINAL"), java.util.Set.of(),
            null, java.util.Set.of(), null, null, 10))).isEmpty();
    }

    private static final class StubPlantCatalog implements PlantCatalog {

        @Override
        public Optional<PlantView> findPlant(UUID plantId) {
            boolean alive = plantId.equals(UUID.nameUUIDFromBytes("plant-alive".getBytes()));
            return Optional.of(new PlantView(plantId, UUID.randomUUID(),
                alive ? "Живой фикус" : "Мёртвый фикус", alive ? "ALIVE" : "DEAD"));
        }
    }

    private static final class StubOwnerDirectory implements OwnerDirectory {

        @Override
        public Optional<OwnerView> findOwner(UUID userId) {
            return Optional.of(new OwnerView(userId, "Владелец"));
        }
    }
}
