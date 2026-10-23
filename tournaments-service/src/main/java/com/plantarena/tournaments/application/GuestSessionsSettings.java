package com.plantarena.tournaments.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "plantarena.guests")
public record GuestSessionsSettings(Duration sessionTtl, int sessionCreationLimitPerMinute,
                                    int voteLimitPerMinute) {

    public GuestSessionsSettings {
        if (sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("plantarena.guests.session-ttl > 0");
        }
        if (sessionCreationLimitPerMinute < 1 || voteLimitPerMinute < 1) {
            throw new IllegalArgumentException("plantarena.guests.*-limit-per-minute >= 1");
        }
    }
}
