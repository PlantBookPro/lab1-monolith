package com.plantarena.identity.adapter.in.web;

import com.plantarena.identity.application.port.in.MyProfileUseCase;
import com.plantarena.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Собственный профиль (/me): только идентифицированный пользователь.
 */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "identity")
public class MeController {

    private final MyProfileUseCase myProfile;
    private final CurrentActorProvider currentActorProvider;

    public MeController(MyProfileUseCase myProfile, CurrentActorProvider currentActorProvider) {
        this.myProfile = myProfile;
        this.currentActorProvider = currentActorProvider;
    }

    @GetMapping
    @Operation(operationId = "identity-me", summary = "Собственный профиль без passwordHash")
    public UserResponse me() {
        return UserResponse.from(myProfile.me(currentActorProvider.currentActor()));
    }

    @PutMapping("/location")
    @Operation(operationId = "identity-update-my-location",
        summary = "Обновить координаты (применяются к следующей global epoch)")
    public UserResponse updateLocation(@Valid @RequestBody UpdateLocationRequest request) {
        return UserResponse.from(myProfile.updateLocation(
            currentActorProvider.currentActor(), request.latitude(), request.longitude()));
    }
}
