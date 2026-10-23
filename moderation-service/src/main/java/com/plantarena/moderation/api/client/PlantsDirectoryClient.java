package com.plantarena.moderation.api.client;

import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "plants-service")
public interface PlantsDirectoryClient {

    @GetMapping("/internal/plants")
    List<PendingPlantView> getPlantsByModerationStatus(
        @RequestParam("moderationStatus") String moderationStatus,
        @RequestParam("limit") int limit);

    @PostMapping("/internal/plants/{plantId}/moderation-decision")
    void recordDecision(@PathVariable("plantId") UUID plantId, @RequestBody DecisionRequest request);

    record PendingPlantView(UUID plantId, UUID assetId) {}
    record DecisionRequest(String decision, String reason) {}
}
