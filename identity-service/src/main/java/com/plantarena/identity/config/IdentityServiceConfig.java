package com.plantarena.identity.config;

import com.plantarena.identity.adapter.in.web.DemoHeaderCurrentActorProvider;
import com.plantarena.identity.application.port.in.ResolveActorUseCase;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdentityServiceConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }

    @Bean
    public OpenAPI identityOpenApi() {
        return new OpenAPI().info(new Info()
            .title("Plant Arena Identity Service API")
            .version("1")
            .description("Пользователи, роли, профиль, разрешение актёра (демо-режим ADR-005)"));
    }

    @Bean
    public DemoHeaderCurrentActorProvider currentActorProvider(ResolveActorUseCase resolveActorUseCase) {
        return new DemoHeaderCurrentActorProvider(resolveActorUseCase);
    }
}
