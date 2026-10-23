package com.plantarena.tournaments.api.client.fallback;

import com.plantarena.tournaments.api.client.PlantsDirectoryClient;
import com.plantarena.tournaments.application.DependencyUnavailableException;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PlantsDirectoryClientFallback implements PlantsDirectoryClient {

    @Override
    public PlantView getPlant(UUID plantId) {
        throw new DependencyUnavailableException("plants-service", null);
    }

    @Override
    public ReservationResponse reserve(ReserveRequest request) {
        throw new DependencyUnavailableException("plants-service", null);
    }

    @Override
    public void confirm(UUID reservationId, ConfirmRequest request) {
        throw new DependencyUnavailableException("plants-service", null);
    }

    @Override
    public void release(UUID reservationId) {
        throw new DependencyUnavailableException("plants-service", null);
    }

    @Override
    public void registerDeath(UUID plantId, DeathRequest request) {
        throw new DependencyUnavailableException("plants-service", null);
    }
}
