package com.plantarena.config;

import com.plantarena.shared.event.IntegrationEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Публикация integration-событий в монолите — Spring ApplicationEventPublisher
 * (in-process). В лабах №2/4 реализация меняется здесь на Kafka-издателя:
 * домен и application контекстов не меняются (правило 10.2, ADR-006).
 */
@Configuration
public class EventWiringConfig {

    @Bean
    public IntegrationEventPublisher integrationEventPublisher(ApplicationEventPublisher publisher) {
        return publisher::publishEvent;
    }
}
