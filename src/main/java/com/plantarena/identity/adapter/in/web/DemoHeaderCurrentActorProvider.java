package com.plantarena.identity.adapter.in.web;

import com.plantarena.identity.application.port.in.ResolveActorUseCase;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.shared.security.NotIdentifiedException;
import java.util.UUID;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Демо-идентификация через X-Demo-User-Id (ADR-005): только профили dev/test,
 * связывание — config.IdentityWiringConfig. Отсутствие заголовка = гость;
 * неизвестный/неактивный ID = ошибка; роли всегда из БД, не из заголовка.
 * Это механизм демонстрации, не аутентификация.
 */
public class DemoHeaderCurrentActorProvider implements CurrentActorProvider {

    public static final String DEMO_USER_ID_HEADER = "X-Demo-User-Id";

    private final ResolveActorUseCase resolveActor;

    public DemoHeaderCurrentActorProvider(ResolveActorUseCase resolveActor) {
        this.resolveActor = resolveActor;
    }

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
        return resolveActor.resolve(userId);
    }
}
