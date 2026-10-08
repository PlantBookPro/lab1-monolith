package com.plantarena.config;

import com.plantarena.identity.adapter.in.web.DemoHeaderCurrentActorProvider;
import com.plantarena.identity.application.port.in.ResolveActorUseCase;
import com.plantarena.shared.security.CurrentActorProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;


@Configuration
public class IdentityWiringConfig {

    @Bean
    public CurrentActorProvider currentActorProvider(Environment environment,
                                                     ResolveActorUseCase resolveActorUseCase) {
        if (environment.matchesProfiles("dev", "test")) {
            return new DemoHeaderCurrentActorProvider(resolveActorUseCase);
        }
        throw new IllegalStateException(
            "Адаптер идентификации не настроен: заголовок X-Demo-User-Id разрешён только "
                + "в профилях dev/test (ADR-005); Spring Security + JWT появится в лабе №3");
    }
}
