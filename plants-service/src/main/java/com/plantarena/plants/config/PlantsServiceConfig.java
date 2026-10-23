package com.plantarena.plants.config;

import com.plantarena.shared.event.IntegrationEventPublisher;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlantsServiceConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }

    @Bean
    public IntegrationEventPublisher integrationEventPublisher(ApplicationEventPublisher publisher) {
        return publisher::publishEvent;
    }

    @Bean
    public OpenAPI plantsOpenApi() {
        return new OpenAPI().info(new Info()
            .title("Plant Arena Plants Service API").version("1")
            .description("Растения: отправка, модерация, жизненный цикл, резервы, внутренние эндпоинты"));
    }
}
