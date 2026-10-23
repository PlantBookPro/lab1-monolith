package com.plantarena.shared.security;

import java.util.Set;
import java.util.UUID;


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
