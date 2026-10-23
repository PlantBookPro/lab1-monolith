package com.plantarena.media.adapter.in.web;

import com.plantarena.media.api.client.IdentityDirectoryClient;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import com.plantarena.shared.security.NotIdentifiedException;
import feign.FeignException;
import java.util.EnumSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
@RequiredArgsConstructor
public class RemoteHeaderCurrentActorProvider implements CurrentActorProvider {

    public static final String DEMO_USER_ID_HEADER = "X-Demo-User-Id";

    private final IdentityDirectoryClient identityClient;

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
        try {
            IdentityDirectoryClient.ActorView view = identityClient.getActor(userId);
            EnumSet<AppRole> roles = EnumSet.noneOf(AppRole.class);
            view.roles().forEach(role -> roles.add(AppRole.valueOf(role)));
            return CurrentActor.identified(view.userId(), roles);
        } catch (FeignException.NotFound e) {
            throw new NotIdentifiedException("Неизвестный или деактивированный пользователь: " + userId);
        }
    }
}
