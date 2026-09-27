package com.plantarena.geo.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки кластеризации (раздел 8): точность geohash-сетки, задаётся
 * конфигурацией и фиксируется в версии политики (1–12).
 */
@ConfigurationProperties(prefix = "plantarena.geo")
public record GeoClusteringSettings(int geohashPrecision) {

    public GeoClusteringSettings {
        if (geohashPrecision < 1 || geohashPrecision > 12) {
            throw new IllegalArgumentException(
                "plantarena.geo.geohash-precision вне диапазона 1–12: " + geohashPrecision);
        }
    }
}
