package com.plantarena.config;

import com.plantarena.moderation.adapter.out.classifier.OnnxPlantClassifier;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Связывание классификатора модерации (ADR-009): ONNX-адаптер основного
 * профиля; путь модели — конфиг plantarena.moderation.model-path. Бин не
 * требует файла модели при создании (честная незавершённость при classify).
 * @EnableScheduling — poller заданий модерации (fixedDelay 2с). config —
 * единственное место, знающее несколько контекстов (раздел 10.2, правило 9).
 */
@Configuration
@EnableScheduling
public class ModerationWiringConfig {

    @Bean
    public PlantClassifier plantClassifier(
            @Value("${plantarena.moderation.model-path}") String modelPath) {
        return new OnnxPlantClassifier(Path.of(modelPath));
    }
}
