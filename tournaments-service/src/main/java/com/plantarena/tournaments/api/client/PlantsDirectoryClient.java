package com.plantarena.tournaments.api.client;

import com.plantarena.tournaments.api.client.fallback.PlantsDirectoryClientFallbackFactory;
import java.time.Instant;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "plants-service", fallbackFactory = PlantsDirectoryClientFallbackFactory.class)
public interface PlantsDirectoryClient {

    @GetMapping("/internal/plants/{plantId}")
    PlantView getPlant(@PathVariable("plantId") UUID plantId);

    @PostMapping("/internal/plants/reservations")
    ReservationResponse reserve(@RequestBody ReserveRequest request);

    @PostMapping("/internal/plants/reservations/{reservationId}/confirm")
    void confirm(@PathVariable("reservationId") UUID reservationId, @RequestBody ConfirmRequest request);

    @PostMapping("/internal/plants/reservations/{reservationId}/release")
    void release(@PathVariable("reservationId") UUID reservationId);

    @PostMapping("/internal/plants/{plantId}/death")
    void registerDeath(@PathVariable("plantId") UUID plantId, @RequestBody DeathRequest request);

    record PlantView(UUID id, UUID ownerId, UUID assetId, String title,
                     String moderationStatus, String lifeStatus) {}

    record ReserveRequest(UUID ownerId, UUID plantId, UUID idempotencyKey) {}

    record ReservationResponse(UUID reservationId) {}

    record ConfirmRequest(UUID ownerId, UUID plantId) {}

    record DeathRequest(String kind, Instant cooldownExpiresAt, String reason, UUID sourceEntryId) {}
}
