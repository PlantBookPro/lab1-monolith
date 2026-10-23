package com.plantarena.moderation.config;

import com.plantarena.moderation.adapter.out.classifier.OnnxPlantClassifier;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class ModerationServiceConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }

    @Bean
    public PlantClassifier plantClassifier(
            @Value("${plantarena.moderation.model-path}") String modelPath) {
        return new OnnxPlantClassifier(Path.of(modelPath));
    }

    @Bean
    public OpenAPI moderationOpenApi() {
        return new OpenAPI().info(new Info()
            .title("Plant Arena Moderation Service API").version("1")
            .description("Автоматическая модерация растений: ONNX-классификатор, задания, poller'ы"));
    }
}
