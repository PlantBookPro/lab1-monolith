package com.plantarena.plants.application;

import com.plantarena.plants.api.PlantLifecycle;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.api.event.PlantDiedEvent;
import com.plantarena.plants.application.support.FakeIntegrationEventPublisher;
import com.plantarena.plants.application.support.InMemoryImageRestrictionRepository;
import com.plantarena.plants.application.support.InMemoryPlantRepository;
import com.plantarena.plants.domain.ImageFingerprint;
import com.plantarena.plants.domain.ImageReusePolicy;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import com.plantarena.plants.domain.RestrictionKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Контракт PlantLifecycle: гибель, запреты, идемпотентность")
class PlantLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final ImageFingerprint FINGERPRINT =
        new ImageFingerprint("5".repeat(64), 1);

    private final PlantRepository plants = new InMemoryPlantRepository();
    private final InMemoryImageRestrictionRepository restrictions =
        new InMemoryImageRestrictionRepository();
    private final FakeIntegrationEventPublisher events = new FakeIntegrationEventPublisher();
    private final PlantLifecycleService service = new PlantLifecycleService(
        plants, restrictions, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private UUID submittedPlant(UUID ownerId) {
        Plant plant = Plant.submit(ownerId, UUID.randomUUID(), FINGERPRINT, "Фикус", NOW);
        plants.save(plant);
        return plant.id();
    }

    @Test
    void погибшее_растение_не_воскресает_повторная_гибель_создаёт_запрет_один_раз() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId);

        service.registerDeath(plantId, PlantLifecycle.RestrictionKind.PERMANENT, null,
            "поражение в закрытом турнире", UUID.randomUUID());
        service.registerDeath(plantId, PlantLifecycle.RestrictionKind.PERMANENT, null,
            "поражение в закрытом турнире", UUID.randomUUID()); // повтор доставки

        assertThat(plants.findById(plantId).orElseThrow().lifeStatus().name()).isEqualTo("DEAD");
        assertThat(restrictions.restrictions).hasSize(1); // дубля запрета нет
        assertThat(events.published).hasSize(1);
        PlantDiedEvent event = (PlantDiedEvent) events.published.get(0);
        assertThat(event.eventType()).isEqualTo("PlantDied");
        assertThat(event.payload().restrictionKind()).isEqualTo("PERMANENT");
    }

    @Test
    void постоянный_запрет_приоритетнее_временного_при_накоплении() {
        UUID ownerId = UUID.randomUUID();
        UUID first = submittedPlant(ownerId);
        UUID second = submittedPlant(ownerId);

        service.registerDeath(first, PlantLifecycle.RestrictionKind.COOLDOWN,
            NOW.plus(Duration.ofHours(24)), "поражение в глобальном турнире", null);
        service.registerDeath(second, PlantLifecycle.RestrictionKind.PERMANENT, null,
            "поражение в закрытом турнире", null);

        assertThat(restrictions.findByOwnerAndFingerprint(ownerId, FINGERPRINT.value()))
            .hasSize(2); // история не удаляется
        var active = new ImageReusePolicy()
            .activeRestriction(restrictions.findByOwnerAndFingerprint(ownerId, FINGERPRINT.value()),
                NOW.plusSeconds(60));
        assertThat(active).isPresent();
        assertThat(active.get().kind()).isEqualTo(RestrictionKind.PERMANENT);
    }

    @Test
    void суточный_запрет_хранит_срок_истечения() {
        UUID ownerId = UUID.randomUUID();
        UUID plantId = submittedPlant(ownerId);

        service.registerDeath(plantId, PlantLifecycle.RestrictionKind.COOLDOWN,
            NOW.plus(Duration.ofHours(24)), "поражение в глобальном турнире", null);

        assertThat(restrictions.restrictions).hasSize(1);
        assertThat(restrictions.restrictions.get(0).expiresAt())
            .isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void несогласованные_аргументы_запрета_отклоняются() {
        UUID plantId = submittedPlant(UUID.randomUUID());

        assertThatThrownBy(() -> service.registerDeath(plantId,
            PlantLifecycle.RestrictionKind.COOLDOWN, null, "без срока", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.registerDeath(plantId,
            PlantLifecycle.RestrictionKind.PERMANENT, NOW.plusSeconds(1), "со сроком", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void несуществующее_растение_404() {
        assertThatThrownBy(() -> service.registerDeath(UUID.randomUUID(),
            PlantLifecycle.RestrictionKind.PERMANENT, null, "ничего", null))
            .isInstanceOf(PlantNotFoundException.class);
    }
}
