package com.plantarena.tournaments.application.support;

import com.plantarena.tournaments.application.port.out.PlantLifecycleGateway;
import com.plantarena.tournaments.application.port.out.PlantLifecycleGateway.RestrictionKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Фейк порта гибели: запись вызовов для утверждений о командах plants. */
public class FakePlantLifecycleGateway implements PlantLifecycleGateway {

    public final List<DeathCall> calls = new ArrayList<>();

    @Override
    public void registerDeath(UUID plantId, RestrictionKind kind, Instant cooldownExpiresAt,
                              String reason, UUID sourceEntryId) {
        calls.add(new DeathCall(plantId, kind, cooldownExpiresAt, reason, sourceEntryId));
    }

    public boolean isDead(UUID plantId) {
        return calls.stream().anyMatch(call -> call.plantId().equals(plantId));
    }

    public record DeathCall(UUID plantId, RestrictionKind kind, Instant cooldownExpiresAt,
                            String reason, UUID sourceEntryId) {
    }
}
