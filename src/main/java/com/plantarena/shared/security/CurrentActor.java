package com.plantarena.shared.security;

import java.util.Set;
import java.util.UUID;

/**
 * Технический тип текущего субъекта (раздел 2): userId, роли, признак гостя.
 * Живёт в shared.security; провайдера реализует контекст identity.
 */
public record CurrentActor(UUID userId, Set<AppRole> roles, boolean isGuest) {

    public CurrentActor {
        roles = Set.copyOf(roles);
    }

    public static CurrentActor guest() {
        return new CurrentActor(null, Set.of(), true);
    }

    public static CurrentActor identified(UUID userId, Set<AppRole> roles) {
        return new CurrentActor(userId, roles, false);
    }

    public boolean hasRole(AppRole role) {
        return roles.contains(role);
    }
}
