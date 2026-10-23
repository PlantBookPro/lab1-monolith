package com.plantarena.plants.adapter.in.web;

import com.plantarena.plants.api.PlantDirectory;
import com.plantarena.plants.api.PlantEligibility;
import com.plantarena.plants.api.PlantLifecycle;
import com.plantarena.plants.api.PlantModeration;
import com.plantarena.plants.api.PlantNotFoundException;
import com.plantarena.plants.application.port.in.QueryPendingPlantsUseCase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalPlantController {

    private final PlantDirectory plantDirectory;
    private final PlantModeration plantModeration;
    private final PlantLifecycle plantLifecycle;
    private final PlantEligibility plantEligibility;
    private final QueryPendingPlantsUseCase pendingPlants;

    @GetMapping("/plants/{plantId}")
    public PlantDataResponse get(@PathVariable UUID plantId) {
        return plantDirectory.findById(plantId)
            .map(p -> new PlantDataResponse(p.id(), p.ownerId(), p.assetId(), p.title(),
                p.moderationStatus().name(), p.lifeStatus().name()))
            .orElseThrow(() -> new PlantNotFoundException("Растение не найдено: " + plantId));
    }

    @GetMapping("/plants")
    public List<PendingPlantResponse> pending(
            @RequestParam("moderationStatus") String moderationStatus,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return pendingPlants.findPending(limit).stream()
            .map(p -> new PendingPlantResponse(p.plantId(), p.assetId()))
            .toList();
    }

    @PostMapping("/plants/{plantId}/moderation-decision")
    public PlantDataResponse decide(@PathVariable UUID plantId, @RequestBody DecisionRequest request) {
        var data = plantModeration.recordDecision(plantId,
            PlantModeration.Decision.valueOf(request.decision()), request.reason());
        return new PlantDataResponse(data.id(), data.ownerId(), data.assetId(), data.title(),
            data.moderationStatus().name(), data.lifeStatus().name());
    }

    @PostMapping("/plants/{plantId}/death")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void die(@PathVariable UUID plantId, @RequestBody DeathRequest request) {
        plantLifecycle.registerDeath(plantId,
            PlantLifecycle.RestrictionKind.valueOf(request.kind()),
            request.cooldownExpiresAt(), request.reason(), request.sourceEntryId());
    }

    @PostMapping("/plants/reservations")
    public ReservationResponse reserve(@RequestBody ReserveRequest request) {
        UUID reservationId = plantEligibility.reserveSubmission(
            request.ownerId(), request.plantId(), request.idempotencyKey());
        return new ReservationResponse(reservationId);
    }

    @PostMapping("/plants/reservations/{reservationId}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@PathVariable UUID reservationId, @RequestBody ConfirmRequest request) {
        plantEligibility.confirmEligibility(request.ownerId(), request.plantId(), reservationId);
    }

    @PostMapping("/plants/reservations/{reservationId}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID reservationId) {
        plantEligibility.releaseReservation(reservationId);
    }

    public record PlantDataResponse(UUID id, UUID ownerId, UUID assetId, String title,
                                    String moderationStatus, String lifeStatus) {}
    public record PendingPlantResponse(UUID plantId, UUID assetId) {}
    public record DecisionRequest(String decision, String reason) {}
    public record DeathRequest(String kind, Instant cooldownExpiresAt, String reason, UUID sourceEntryId) {}
    public record ReserveRequest(UUID ownerId, UUID plantId, UUID idempotencyKey) {}
    public record ReservationResponse(UUID reservationId) {}
    public record ConfirmRequest(UUID ownerId, UUID plantId) {}
}
