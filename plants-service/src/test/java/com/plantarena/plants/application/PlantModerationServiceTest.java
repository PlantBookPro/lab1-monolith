package com.plantarena.plants.application;

import com.plantarena.plants.api.ModerationAlreadyDecidedException;
import com.plantarena.plants.api.PlantModeration;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.event.PlantModerationDecidedEvent;
import com.plantarena.plants.application.support.FakeIntegrationEventPublisher;
import com.plantarena.plants.application.support.FakeMediaAssetClaims;
import com.plantarena.plants.application.support.InMemoryPlantRepository;
import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Контракт PlantModeration: решение командой, идемпотентность, публичность файла")
class PlantModerationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    private final PlantRepository plants = new InMemoryPlantRepository();
    private final FakeMediaAssetClaims mediaClaims = new FakeMediaAssetClaims();
    private final FakeIntegrationEventPublisher events = new FakeIntegrationEventPublisher();
    private final PlantModerationService service =
        new PlantModerationService(plants, mediaClaims, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private UUID submittedPlant(UUID ownerId, UUID assetId) {
        Plant plant = Plant.submit(ownerId, assetId,
            new ImageFingerprint("3".repeat(64), 1), "Фикус", NOW);
        plants.save(plant);
        return plant.id();
    }

    @Test
    void одобрение_фиксирует_решение_делает_файл_публичным_и_публикует_событие() {
        UUID ownerId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId, assetId);

        var result = service.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);

        assertThat(result.moderationStatus().name()).isEqualTo("APPROVED");
        assertThat(mediaClaims.publicAssets).containsExactly(assetId);
        assertThat(events.published).hasSize(1);
        PlantModerationDecidedEvent event = (PlantModerationDecidedEvent) events.published.get(0);
        assertThat(event.eventType()).isEqualTo("PlantModerationDecided");
        assertThat(event.payload().decision()).isEqualTo("APPROVED");
    }

    @Test
    void отклонение_сохраняет_причину_и_не_делает_файл_публичным() {
        UUID assetId = UUID.randomUUID();
        UUID plantId = submittedPlant(UUID.randomUUID(), assetId);

        var result = service.recordDecision(plantId, PlantModeration.Decision.REJECTED, "не растение");

        assertThat(result.moderationStatus().name()).isEqualTo("REJECTED");
        assertThat(mediaClaims.publicAssets).isEmpty();
        assertThat(mediaClaims.claimedAssets).isEmpty(); // claim не вызывался повторно
    }

    @Test
    void повтор_того_же_решения_идемпотентен_без_второго_события() {
        UUID plantId = submittedPlant(UUID.randomUUID(), UUID.randomUUID());
        service.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);

        service.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);

        assertThat(events.published).hasSize(1);
    }

    @Test
    void конфликтующее_решение_отклоняется() {
        UUID plantId = submittedPlant(UUID.randomUUID(), UUID.randomUUID());
        service.recordDecision(plantId, PlantModeration.Decision.APPROVED, null);

        assertThatThrownBy(() ->
            service.recordDecision(plantId, PlantModeration.Decision.REJECTED, "опоздало"))
            .isInstanceOf(ModerationAlreadyDecidedException.class);
    }

    @Test
    void несуществующее_растение_404() {
        assertThatThrownBy(() ->
            service.recordDecision(UUID.randomUUID(), PlantModeration.Decision.APPROVED, null))
            .isInstanceOf(PlantNotFoundException.class);
    }
}
