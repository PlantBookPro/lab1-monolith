package com.plantarena.identity.application;

import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.util.UUID;
import org.springframework.stereotype.Component;


@Component
public class IdentityAccessPolicy {

    
    public void requireUserManagement(CurrentActor actor) {
        requireIdentified(actor);
        if (!actor.hasRole(AppRole.MODERATOR) && !actor.hasRole(AppRole.ADMIN)) {
            throw new AccessDeniedException(
                "Создание и просмотр пользователей доступно модератору или администратору");
        }
    }

    
    public void requireViewUser(CurrentActor actor, UUID targetUserId) {
        requireIdentified(actor);
        if (isSelf(actor, targetUserId) || actor.hasRole(AppRole.MODERATOR) || actor.hasRole(AppRole.ADMIN)) {
            return;
        }
        throw new AccessDeniedException(
            "Просмотр профиля доступен самому пользователю, модератору или администратору");
    }

    
    public void requireEditProfile(CurrentActor actor, UUID targetUserId) {
        requireIdentified(actor);
        if (isSelf(actor, targetUserId) || actor.hasRole(AppRole.ADMIN)) {
            return;
        }
        throw new AccessDeniedException("Изменение профиля доступно владельцу или администратору");
    }

    
    public void requireAdmin(CurrentActor actor) {
        requireIdentified(actor);
        if (!actor.hasRole(AppRole.ADMIN)) {
            throw new AccessDeniedException("Действие доступно только администратору");
        }
    }

    public void requireIdentified(CurrentActor actor) {
        if (actor == null || actor.isGuest()) {
            throw new NotIdentifiedException(
                "Требуется идентифицированный пользователь (X-Demo-User-Id в dev/test, ADR-005)");
        }
    }

    private boolean isSelf(CurrentActor actor, UUID targetUserId) {
        return actor.userId() != null && actor.userId().equals(targetUserId);
    }
}
