package com.plantarena.moderation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.plantarena.moderation", "com.plantarena.shared.web"})
@EnableFeignClients(basePackages = "com.plantarena.moderation.api.client")
@EnableScheduling
public class ModerationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ModerationServiceApplication.class, args);
    }
}
