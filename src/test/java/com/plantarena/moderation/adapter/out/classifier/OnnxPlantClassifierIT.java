package com.plantarena.moderation.adapter.out.classifier;

import com.plantarena.moderation.application.port.out.PlantClassifier;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Настоящий ONNX-инференс MobileNetV2 на эталонных фотографиях (ADR-009).
 * Тег inference: в обычный verify не входит (failsafe excludedGroups);
 * запуск: ./mvnw verify -P inference. Без модели (offline-сборка) — честный
 * skip с сообщением (assumption), не ошибка.
 * Фото: Wikimedia Commons — «Five daisies (Bellis perennis)» и
 * «Golden Retriever dog at MAV-USP» (уменьшенные до 400px, CC-лицензии
 * указаны на страницах файлов).
 */
@Tag("inference")
@DisplayName("ONNX-инференс MobileNetV2: ромашка — растение, собака — нет (ADR-009)")
class OnnxPlantClassifierIT {

    private static final Path MODEL = Path.of("target/models/mobilenetv2-1.0.onnx");

    private final OnnxPlantClassifier classifier = new OnnxPlantClassifier(MODEL);

    @BeforeAll
    static void модель_должна_быть_скачана() {
        Assumptions.assumeTrue(Files.isReadable(MODEL),
            "Модель не скачана (offline): " + MODEL.toAbsolutePath()
                + " — повторите сборку онлайн или скачайте вручную (README «Модерация»)");
    }

    @Test
    @DisplayName("фото ромашки распознаётся как растение")
    void ромашка_растение() throws Exception {
        PlantClassifier.Classification result = classifier.classify(reference("daisy.jpg"));

        assertThat(result.modelVersion()).isEqualTo(OnnxPlantClassifier.MODEL_VERSION);
        assertThat(result.confidence()).isBetween(0f, 1f);
        assertThat(result.plant())
            .as("top-1 должен попасть в растительные классы с confidence >= 0.35, "
                + "фактическая confidence = %s", result.confidence())
            .isTrue();
    }

    @Test
    @DisplayName("фото собаки — не растение")
    void собака_не_растение() throws Exception {
        PlantClassifier.Classification result = classifier.classify(reference("dog.jpg"));

        assertThat(result.plant())
            .as("фактическая confidence = %s", result.confidence())
            .isFalse();
    }

    private byte[] reference(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/moderation/reference/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Эталонное фото не найдено: " + name);
            }
            return in.readAllBytes();
        }
    }
}
