package com.plantarena.tournaments.config;

import com.plantarena.tournaments.application.FixedWindowRateLimiter;
import com.plantarena.tournaments.application.GlobalCompetitionSettings;
import com.plantarena.tournaments.application.GuestSessionsSettings;
import com.plantarena.shared.event.IntegrationEventPublisher;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({GlobalCompetitionSettings.class, GuestSessionsSettings.class})
public class TournamentsServiceConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }

    @Bean
    public FixedWindowRateLimiter guestRateLimiter(Clock clock) {
        return new FixedWindowRateLimiter(clock);
    }

    @Bean
    public IntegrationEventPublisher integrationEventPublisher(ApplicationEventPublisher publisher) {
        return publisher::publishEvent;
    }

    @Bean
    public OpenAPI tournamentsOpenApi() {
        return new OpenAPI().info(new Info()
            .title("Plant Arena Tournaments Service API").version("1")
            .description("Турниры: закрытые соревнования, приглашения, голосование, глобальное соревнование, гостевые сессии"));
    }
}
