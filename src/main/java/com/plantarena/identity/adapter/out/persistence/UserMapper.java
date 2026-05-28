package com.plantarena.identity.adapter.out.persistence;

import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.GeoPoint;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRole;
import java.util.EnumSet;
import java.util.stream.Collectors;

/**
 * Явный маппинг JPA-модели на домен (раздел 5 требований).
 */
final class UserMapper {

    private UserMapper() {
    }

    static User toDomain(UserJpaEntity entity) {
        java.util.Set<UserRole> roles = entity.roles().stream()
            .map(RoleJpaEntity::code)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(UserRole.class)));
        GeoPoint location = entity.latitude() == null || entity.longitude() == null
            ? null
            : new GeoPoint(entity.latitude(), entity.longitude());
        return User.restore(entity.id(), new Email(entity.email()), entity.displayName(),
            entity.passwordHash(), roles, entity.status(), location, entity.versionValue());
    }
}
