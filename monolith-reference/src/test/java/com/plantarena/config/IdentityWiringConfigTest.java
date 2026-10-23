package com.plantarena.config;

import com.plantarena.identity.adapter.in.web.DemoHeaderCurrentActorProvider;
import com.plantarena.identity.application.port.in.ResolveActorUseCase;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.CurrentActorProvider;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Связывание адаптера идентификации (ADR-005)")
class IdentityWiringConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(IdentityWiringConfig.class)
        .withBean(ResolveActorUseCase.class,
            () -> userId -> CurrentActor.identified(userId, Set.of()));

    @Test
    void обычный_профиль_без_адаптера_идентификации_падает_при_старте() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasMessageContaining("Адаптер идентификации не настроен");
        });
    }

    @Test
    void профиль_test_подключает_демо_адаптер() {
        runner.withPropertyValues("spring.profiles.active=test").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CurrentActorProvider.class);
            assertThat(context).hasSingleBean(DemoHeaderCurrentActorProvider.class);
        });
    }

    @Test
    void профиль_dev_подключает_демо_адаптер() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DemoHeaderCurrentActorProvider.class);
        });
    }
}
