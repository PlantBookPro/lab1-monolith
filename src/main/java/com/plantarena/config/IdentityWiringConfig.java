package com.plantarena.config;

import com.plantarena.identity.adapter.in.web.DemoHeaderCurrentActorProvider;
import com.plantarena.identity.application.port.in.ResolveActorUseCase;
import com.plantarena.shared.security.CurrentActorProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Связывание адаптера идентификации (ADR-005). config — единственное место,
 * знающее несколько контекстов (раздел 10.2, правило 9). Обычный профиль без
 * адаптера идентификации завершается явной ошибкой конфигурации при старте;
 * Spring Security + JWT появится в лабе №3 (замена только этого бина).
 */
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
