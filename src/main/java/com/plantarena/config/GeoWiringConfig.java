package com.plantarena.config;

import com.plantarena.geo.application.GeoClusteringSettings;
import com.plantarena.geo.domain.ClusteringPolicy;
import com.plantarena.geo.domain.GeohashClusteringPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
@EnableConfigurationProperties(GeoClusteringSettings.class)
public class GeoWiringConfig {

    @Bean
    public ClusteringPolicy geohashClusteringPolicy(GeoClusteringSettings settings) {
        return new GeohashClusteringPolicy(settings.geohashPrecision());
    }
}
