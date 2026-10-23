package com.plantarena.feed;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.plantarena.feed", "com.plantarena.shared.webflux"})
@EnableFeignClients(basePackages = "com.plantarena.feed.api.client")
public class FeedServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FeedServiceApplication.class, args);
    }
}
