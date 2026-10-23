package com.plantarena.plants.api.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "tournaments-service")
public interface TournamentsEventsClient {

    @PostMapping("/internal/events/plant-moderation-decided")
    void plantModerationDecided(@RequestBody PlantModerationDecidedPayload payload);

    record PlantModerationDecidedPayload(UUID plantId, String decision) {}
}
