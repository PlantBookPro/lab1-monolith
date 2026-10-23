package com.plantarena.media.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MediaServiceConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }

    @Bean
    public OpenAPI mediaOpenApi() {
        return new OpenAPI().info(new Info()
            .title("Plant Arena Media Service API").version("1")
            .description("Загрузка/скачивание/удаление изображений, клеймы ассетов"));
    }
}
