package com.plantarena.tournaments.adapter.in.web;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Component
public class ActorContextWebFilter implements WebFilter, Ordered {

    public static final String DEMO_USER_ID_HEADER = "X-Demo-User-Id";
    public static final String ACTOR_ID_CONTEXT_KEY = "plantarena.actorId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst(DEMO_USER_ID_HEADER);
        Context context = Context.of(ACTOR_ID_CONTEXT_KEY, header == null ? "" : header.trim());
        return chain.filter(exchange).contextWrite(context);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
