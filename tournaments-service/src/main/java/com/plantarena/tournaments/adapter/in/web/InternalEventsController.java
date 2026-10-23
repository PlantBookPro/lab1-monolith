package com.plantarena.tournaments.adapter.in.web;

import com.plantarena.shared.webflux.Blocking;
import com.plantarena.tournaments.application.port.in.OnPlantModerationDecidedUseCase;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal/events")
@RequiredArgsConstructor
public class InternalEventsController {

    private final OnPlantModerationDecidedUseCase onPlantModerationDecided;

    @PostMapping("/plant-moderation-decided")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> plantModerationDecided(@RequestBody PlantModerationDecidedRequest request) {
        return Blocking.call(() -> {
            onPlantModerationDecided.onPlantModerationDecided(request.plantId(), request.decision());
            return null;
        }).then();
    }

    public record PlantModerationDecidedRequest(UUID plantId, String decision) {}
}
