package com.plantarena.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Единственный источник времени приложения: UTC (раздел 5 требований).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}
