package com.plantarena.config;

import com.plantarena.tournaments.application.GlobalCompetitionSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Связывание контекста tournaments: настройки глобального турнира. */
@Configuration
@EnableConfigurationProperties(GlobalCompetitionSettings.class)
public class TournamentsWiringConfig {
}
