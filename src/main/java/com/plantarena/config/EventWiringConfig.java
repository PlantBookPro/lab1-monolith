package com.plantarena.config;

import com.plantarena.shared.event.IntegrationEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class EventWiringConfig {

    @Bean
    public IntegrationEventPublisher integrationEventPublisher(ApplicationEventPublisher publisher) {
        return publisher::publishEvent;
    }
}
