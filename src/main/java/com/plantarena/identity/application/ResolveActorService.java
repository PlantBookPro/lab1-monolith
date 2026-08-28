package com.plantarena.identity.application;

import com.plantarena.identity.application.port.in.ResolveActorUseCase;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import com.plantarena.identity.domain.UserStatus;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Идентификация субъекта по userId (ADR-005): роли всегда из БД,
 * неизвестный/деактивированный ID — ошибка идентификации.
 */
@Service
@Transactional(readOnly = true)
public class ResolveActorService implements ResolveActorUseCase {

    private final UserRepository users;

    public ResolveActorService(UserRepository users) {
        this.users = users;
    }

    @Override
    public CurrentActor resolve(UUID userId) {
        User user = users.findById(userId)
            .orElseThrow(() -> new NotIdentifiedException("Неизвестный X-Demo-User-Id: " + userId));
        if (user.status() != UserStatus.ACTIVE) {
            throw new NotIdentifiedException("Пользователь деактивирован: " + userId);
        }
        Set<AppRole> roles = user.roles().stream()
            .map(role -> AppRole.valueOf(role.name()))
            .collect(Collectors.toUnmodifiableSet());
        return CurrentActor.identified(user.id(), roles);
    }
}
