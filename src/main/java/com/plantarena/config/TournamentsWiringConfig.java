package com.plantarena.config;

import com.plantarena.tournaments.application.FixedWindowRateLimiter;
import com.plantarena.tournaments.application.GlobalCompetitionSettings;
import com.plantarena.tournaments.application.GuestSessionsSettings;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
@EnableConfigurationProperties({GlobalCompetitionSettings.class, GuestSessionsSettings.class})
public class TournamentsWiringConfig {

    
    @Bean
    public FixedWindowRateLimiter guestRateLimiter(Clock clock) {
        return new FixedWindowRateLimiter(clock);
    }
}
