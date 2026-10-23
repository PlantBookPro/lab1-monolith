package com.plantarena.tournaments.adapter.out.plants;

import com.plantarena.tournaments.api.client.PlantsDirectoryClient;
import com.plantarena.tournaments.application.port.out.PlantLifecycleGateway;
import com.plantarena.tournaments.application.port.out.PlantLifecycleGateway.RestrictionKind;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpPlantLifecycle implements PlantLifecycleGateway {

    private final PlantsDirectoryClient plantsClient;

    @Override
    public void registerDeath(UUID plantId, RestrictionKind kind, Instant cooldownExpiresAt,
                              String reason, UUID sourceEntryId) {
        plantsClient.registerDeath(plantId, new PlantsDirectoryClient.DeathRequest(
            kind.name(), cooldownExpiresAt, reason, sourceEntryId));
    }
}
