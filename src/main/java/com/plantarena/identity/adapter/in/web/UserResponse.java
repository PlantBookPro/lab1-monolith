package com.plantarena.identity.adapter.in.web;

import com.plantarena.identity.application.UserResult;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ответ без passwordHash: пароль никогда не присутствует в ответах (ADR-005).
 */
public record UserResponse(UUID id, String email, String displayName, Set<String> roles,
                           String status, Double latitude, Double longitude) {

    static UserResponse from(UserResult user) {
        return new UserResponse(user.id(), user.email(), user.displayName(),
            user.roles().stream().map(Enum::name).collect(Collectors.toSet()),
            String.valueOf(user.status()), user.latitude(), user.longitude());
    }
}
