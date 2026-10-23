package com.plantarena.feed.api.client;

import com.plantarena.feed.api.client.fallback.IdentityDirectoryClientFallbackFactory;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "identity-service", fallbackFactory = IdentityDirectoryClientFallbackFactory.class)
public interface IdentityDirectoryClient {

    @GetMapping("/internal/actors/{userId}")
    ActorView getActor(@PathVariable("userId") UUID userId);

    @GetMapping("/internal/users/{userId}/profile")
    UserProfileView getProfile(@PathVariable("userId") UUID userId);

    record ActorView(UUID userId, List<String> roles) {}
    record UserProfileView(UUID id, String displayName, boolean active) {}
}
