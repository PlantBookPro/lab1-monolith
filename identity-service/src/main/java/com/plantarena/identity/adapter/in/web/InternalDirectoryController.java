package com.plantarena.identity.adapter.in.web;

import com.plantarena.identity.api.UserDirectory;
import com.plantarena.identity.application.port.in.ResolveActorUseCase;
import com.plantarena.shared.security.CurrentActor;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalDirectoryController {

    private final ResolveActorUseCase resolveActor;
    private final UserDirectory userDirectory;

    @GetMapping("/actors/{userId}")
    public ActorResponse actor(@PathVariable UUID userId) {
        CurrentActor actor = resolveActor.resolve(userId);
        return new ActorResponse(actor.userId(),
            actor.roles().stream().map(Enum::name).toList());
    }

    @GetMapping("/users/{userId}/profile")
    public UserProfileResponse profile(@PathVariable UUID userId) {
        return userDirectory.findById(userId)
            .map(u -> new UserProfileResponse(u.id(), u.displayName(), u.active()))
            .orElseThrow(() -> new com.plantarena.identity.application.UserNotFoundException(userId));
    }

    @GetMapping("/users/{userId}/location")
    public UserLocationResponse location(@PathVariable UUID userId) {
        return userDirectory.findLocation(userId)
            .map(l -> new UserLocationResponse(l.latitude(), l.longitude(), l.locationVersion()))
            .orElseThrow(() -> new com.plantarena.identity.application.UserNotFoundException(userId));
    }

    public record ActorResponse(UUID userId, List<String> roles) {}
    public record UserProfileResponse(UUID id, String displayName, boolean active) {}
    public record UserLocationResponse(double latitude, double longitude, long locationVersion) {}
}
