package com.plantarena.tournaments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.plantarena.tournaments", "com.plantarena.shared.webflux"})
@EnableFeignClients(basePackages = "com.plantarena.tournaments.api.client")
public class TournamentsServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TournamentsServiceApplication.class, args);
    }
}
