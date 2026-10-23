package com.plantarena.feed.support;

import com.plantarena.feed.adapter.in.web.ActorContextWebFilter;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import com.plantarena.shared.webflux.ReactiveCurrentActorProvider;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Тестовый дубль ReactiveCurrentActorProvider (ADR-005, профиль test): заголовки
 * X-Demo-User-Id + X-Demo-Roles задают идентификацию без сети — в IT
 * feed-service нет identity-БД. Заменяет Feign-провайдер (@Primary).
 * Реактивный вариант media-дубля: X-Demo-User-Id читается из контекста
 * ActorContextWebFilter, X-Demo-Roles — из контекста собственного WebFilter
 * этого дубля (порядок сразу после ActorContextWebFilter).
 */
@TestConfiguration
@Primary
public class TestHeaderReactiveCurrentActorProvider implements ReactiveCurrentActorProvider, WebFilter, Ordered {

    public static final String DEMO_ROLES_HEADER = "X-Demo-Roles";
    public static final String DEMO_ROLES_CONTEXT_KEY = "plantarena.demoRoles";

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
            userId = UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            return Mono.error(new NotIdentifiedException("Некорректный " + ActorContextWebFilter.DEMO_USER_ID_HEADER + ": " + header));
        }
        EnumSet<AppRole> roles = EnumSet.of(AppRole.USER);
        String rolesHeader = contextView.getOrDefault(DEMO_ROLES_CONTEXT_KEY, "");
        if (!rolesHeader.isBlank()) {
            roles.clear();
            Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .forEach(role -> roles.add(AppRole.valueOf(role)));
        }
        return Mono.just(CurrentActor.identified(userId, roles));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String roles = exchange.getRequest().getHeaders().getFirst(DEMO_ROLES_HEADER);
        Context context = Context.of(DEMO_ROLES_CONTEXT_KEY, roles == null ? "" : roles.trim());
        return chain.filter(exchange).contextWrite(context);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 11;
    }
}
