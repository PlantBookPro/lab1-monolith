package com.plantarena.geo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

@SpringBootApplication(
    scanBasePackages = {"com.plantarena.geo", "com.plantarena.shared.webflux"},
    exclude = {DataSourceAutoConfiguration.class}
)
public class GeoServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(GeoServiceApplication.class, args);
    }
}
