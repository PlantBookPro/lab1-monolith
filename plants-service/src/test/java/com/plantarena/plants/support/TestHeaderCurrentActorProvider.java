package com.plantarena.plants.support;

import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.shared.security.NotIdentifiedException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Тестовый дубль CurrentActorProvider (ADR-005, профиль test): заголовки
 * X-Demo-User-Id + X-Demo-Roles задают идентификацию без сети — в IT
 * plants-service нет identity-БД. Заменяет Feign-провайдер (@Primary).
 */
@TestConfiguration
@Primary
public class TestHeaderCurrentActorProvider implements CurrentActorProvider {

    public static final String DEMO_USER_ID_HEADER = "X-Demo-User-Id";
    public static final String DEMO_ROLES_HEADER = "X-Demo-Roles";

    @Override
    public CurrentActor currentActor() {
        ServletRequestAttributes attributes =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new NotIdentifiedException("Нет текущего HTTP-запроса: идентификация невозможна");
        }
        String header = attributes.getRequest().getHeader(DEMO_USER_ID_HEADER);
        if (header == null || header.isBlank()) {
            return CurrentActor.guest();
        }
        UUID userId;
        try {
            userId = UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            throw new NotIdentifiedException("Некорректный " + DEMO_USER_ID_HEADER + ": " + header);
        }
        EnumSet<AppRole> roles = EnumSet.of(AppRole.USER);
        String rolesHeader = attributes.getRequest().getHeader(DEMO_ROLES_HEADER);
        if (rolesHeader != null && !rolesHeader.isBlank()) {
            roles.clear();
            Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .forEach(role -> roles.add(AppRole.valueOf(role)));
        }
        return CurrentActor.identified(userId, roles);
    }
}
