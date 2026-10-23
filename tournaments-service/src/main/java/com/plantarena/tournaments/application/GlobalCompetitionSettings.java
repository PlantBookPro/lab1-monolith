package com.plantarena.tournaments.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "plantarena.global")
public record GlobalCompetitionSettings(Duration epochDuration, Duration finalWindowDuration) {

    public GlobalCompetitionSettings {
        if (epochDuration == null || epochDuration.isNegative() || epochDuration.isZero()) {
            throw new IllegalArgumentException("plantarena.global.epoch-duration > 0");
        }
        if (finalWindowDuration == null || finalWindowDuration.isNegative()
                || finalWindowDuration.isZero()) {
            throw new IllegalArgumentException("plantarena.global.final-window-duration > 0");
        }
    }
}
