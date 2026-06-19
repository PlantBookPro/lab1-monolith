package com.plantarena.tournaments.adapter.out.plants;

import com.plantarena.plants.api.PlantLifecycle;
import com.plantarena.tournaments.application.port.out.PlantLifecycleGateway;
import com.plantarena.tournaments.application.port.out.PlantLifecycleGateway.RestrictionKind;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * ACL (раздел 4.3): порт гибели tournaments → опубликованный контракт
 * plants.api.PlantLifecycle. В лабе №2 заменяется на сетевой адаптер с
 * повтором (идемпотентность гибели — plants).
 */
@Component
public class InProcessPlantLifecycle implements PlantLifecycleGateway {

    private final PlantLifecycle plantLifecycle;

    public InProcessPlantLifecycle(PlantLifecycle plantLifecycle) {
        this.plantLifecycle = plantLifecycle;
    }

    @Override
    public void registerDeath(UUID plantId, RestrictionKind kind, Instant cooldownExpiresAt,
                              String reason, UUID sourceEntryId) {
        plantLifecycle.registerDeath(plantId, PlantLifecycle.RestrictionKind.valueOf(kind.name()),
            cooldownExpiresAt, reason, sourceEntryId);
    }
}
