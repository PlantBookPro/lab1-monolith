package com.plantarena.geo.config;

import com.plantarena.geo.application.GeoClusteringSettings;
import com.plantarena.geo.domain.ClusteringPolicy;
import com.plantarena.geo.domain.GeohashClusteringPolicy;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GeoClusteringSettings.class)
public class GeoServiceConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ClusteringPolicy geohashClusteringPolicy(GeoClusteringSettings settings) {
        return new GeohashClusteringPolicy(settings.geohashPrecision());
    }

    @Bean
    public OpenAPI geoOpenApi() {
        return new OpenAPI().info(new Info()
            .title("Plant Arena Geo Service API").version("1")
            .description("Гео-кластеризация: фиксация кластеров эпох по geohash, внутренние эндпоинты"));
    }
}
