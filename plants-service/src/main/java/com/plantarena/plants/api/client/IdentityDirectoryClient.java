package com.plantarena.plants.api.client;

import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "identity-service")
public interface IdentityDirectoryClient {

    @GetMapping("/internal/actors/{userId}")
    ActorView getActor(@PathVariable("userId") UUID userId);

    record ActorView(UUID userId, List<String> roles) {}
}
