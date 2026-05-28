package com.plantarena.identity.application.port.in;

import com.plantarena.identity.application.UserResult;
import com.plantarena.shared.security.CurrentActor;
import java.util.List;
import java.util.UUID;

/**
 * Служебные операции над пользователями (раздел 13: /users, роли).
 * Доступ проверяет IdentityAccessPolicy.
 */
public interface UserAdministrationUseCase {

    UserResult create(CurrentActor actor, CreateUserCommand command);

    UserResult get(CurrentActor actor, UUID userId);

    UserListResult list(CurrentActor actor, int page, int size);

    UserResult updateDisplayName(CurrentActor actor, UUID userId, String displayName);

    void deactivate(CurrentActor actor, UUID userId);

    UserResult grantModerator(CurrentActor actor, UUID userId);

    UserResult revokeModerator(CurrentActor actor, UUID userId);

    record CreateUserCommand(String email, String password, String displayName) {
    }

    record UserListResult(List<UserResult> items, long total) {
    }
}
