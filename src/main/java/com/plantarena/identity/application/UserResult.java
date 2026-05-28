package com.plantarena.identity.application;

import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRole;
import com.plantarena.identity.domain.UserStatus;
import java.util.Set;
import java.util.UUID;

/**
 * Результат use case без passwordHash: пароль никогда не покидает домен (ADR-005).
 */
public record UserResult(UUID id, String email, String displayName, Set<UserRole> roles,
                         UserStatus status, Double latitude, Double longitude) {

    public static UserResult from(User user) {
        return new UserResult(user.id(), user.email().value(), user.displayName(), user.roles(),
            user.status(),
            user.location() == null ? null : user.location().latitude(),
            user.location() == null ? null : user.location().longitude());
    }
}
