package com.plantarena.config;

import com.plantarena.moderation.adapter.out.classifier.OnnxPlantClassifier;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;


@Configuration
@EnableScheduling
public class ModerationWiringConfig {

    @Bean
    public PlantClassifier plantClassifier(
            @Value("${plantarena.moderation.model-path}") String modelPath) {
        return new OnnxPlantClassifier(Path.of(modelPath));
    }
}
