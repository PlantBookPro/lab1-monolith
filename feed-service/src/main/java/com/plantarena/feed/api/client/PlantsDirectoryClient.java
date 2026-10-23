package com.plantarena.feed.api.client;

import com.plantarena.feed.api.client.fallback.PlantsDirectoryClientFallback;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "plants-service", fallback = PlantsDirectoryClientFallback.class)
public interface PlantsDirectoryClient {

    @GetMapping("/internal/plants/{plantId}")
    PlantView getPlant(@PathVariable("plantId") UUID plantId);

    record PlantView(UUID id, UUID ownerId, UUID assetId, String title,
                     String moderationStatus, String lifeStatus) {}
}
