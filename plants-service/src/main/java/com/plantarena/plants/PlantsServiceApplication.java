package com.plantarena.plants;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.plantarena.plants", "com.plantarena.shared.web"})
@EnableFeignClients(basePackages = "com.plantarena.plants.api.client")
public class PlantsServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlantsServiceApplication.class, args);
    }
}
