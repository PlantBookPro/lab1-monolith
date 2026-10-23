package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantData;
import com.plantarena.plants.application.support.InMemoryPlantRepository;
import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ModerationStatus;
import com.plantarena.plants.domain.Plant;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Контракт PlantDirectory: данные растения для внутреннего потребителя (tournaments)")
class PlantDirectoryFacadeTest {

    private final InMemoryPlantRepository repository = new InMemoryPlantRepository();
    private final PlantDirectoryFacade facade = new PlantDirectoryFacade(repository);

    @Test
    @DisplayName("findById возвращает PlantData без проверок прав (внутренний контракт монолита)")
    void find_by_id_возвращает_данные() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submitPlant(ownerId, ModerationStatus.APPROVED);

        PlantData data = facade.findById(plantId).orElseThrow();

        assertThat(data.ownerId()).isEqualTo(ownerId);
        assertThat(data.moderationStatus().name()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("неизвестный id — пустой результат")
    void неизвестный_id_пустой_результат() {
        assertThat(facade.findById(UUID.randomUUID())).isEmpty();
    }

    private UUID submitPlant(UUID ownerId, ModerationStatus status) {
        Plant plant = Plant.submit(ownerId, UUID.randomUUID(),
            new ImageFingerprint("a".repeat(64), 1), "Фикус",
            Instant.parse("2026-09-27T10:00:00Z"));
        plant.applyDecision(status, "причина");
        repository.save(plant);
        return plant.id();
    }
}
