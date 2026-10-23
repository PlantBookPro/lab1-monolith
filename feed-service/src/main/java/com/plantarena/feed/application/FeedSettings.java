package com.plantarena.feed.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "plantarena.feed")
public record FeedSettings(String cursorSecret, Duration cursorTtl) {

    public FeedSettings {
        if (cursorSecret == null || cursorSecret.isBlank()) {
            throw new IllegalArgumentException("plantarena.feed.cursor-secret обязателен");
        }
        if (cursorTtl == null || cursorTtl.isNegative() || cursorTtl.isZero()) {
            throw new IllegalArgumentException("plantarena.feed.cursor-ttl > 0");
        }
    }
}
