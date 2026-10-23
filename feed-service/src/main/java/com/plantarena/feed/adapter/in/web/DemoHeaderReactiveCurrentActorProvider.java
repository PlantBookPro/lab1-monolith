package com.plantarena.feed.adapter.in.web;

import com.plantarena.feed.api.client.IdentityDirectoryClient;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import com.plantarena.shared.webflux.Blocking;
import com.plantarena.shared.webflux.ReactiveCurrentActorProvider;
import feign.FeignException;
import java.util.EnumSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

@Component
@RequiredArgsConstructor
public class DemoHeaderReactiveCurrentActorProvider implements ReactiveCurrentActorProvider {

    private final IdentityDirectoryClient identityClient;

    @Override
    public Mono<CurrentActor> currentActor() {
        return Mono.deferContextual(this::fromContext);
    }

    private Mono<CurrentActor> fromContext(ContextView contextView) {
        String header = contextView.getOrDefault(ActorContextWebFilter.ACTOR_ID_CONTEXT_KEY, "");
        if (header.isBlank()) {
            return Mono.just(CurrentActor.guest());
        }
        UUID userId;
        try {
            userId = UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            return Mono.error(new NotIdentifiedException("Некорректный X-Demo-User-Id: " + header));
        }
        return Blocking.call(() -> identityClient.getActor(userId))
            .map(view -> {
                EnumSet<AppRole> roles = EnumSet.noneOf(AppRole.class);
                view.roles().forEach(role -> roles.add(AppRole.valueOf(role)));
                return CurrentActor.identified(view.userId(), roles);
            })
            .onErrorMap(FeignException.NotFound.class,
                e -> new NotIdentifiedException("Неизвестный или деактивированный пользователь: " + userId));
    }
}
