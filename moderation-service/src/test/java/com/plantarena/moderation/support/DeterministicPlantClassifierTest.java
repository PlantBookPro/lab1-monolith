package com.plantarena.moderation.support;

import com.plantarena.moderation.application.port.out.ClassifierUnavailableException;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Детерминированный классификатор: зелёное — растение, иное — нет")
class DeterministicPlantClassifierTest {

    private final DeterministicPlantClassifier classifier = new DeterministicPlantClassifier();

    @Test
    @DisplayName("зелёное изображение — растение")
    void зелёное_изображение_растение() throws Exception {
        PlantClassifier.Classification result = classifier.classify(image(Color.GREEN));

        assertThat(result.plant()).isTrue();
        assertThat(result.confidence()).isEqualTo(0.9f);
        assertThat(result.modelVersion()).isEqualTo(DeterministicPlantClassifier.MODEL_VERSION);
    }

    @Test
    @DisplayName("красное изображение — не растение")
    void красное_изображение_не_растение() throws Exception {
        assertThat(classifier.classify(image(Color.RED)).plant()).isFalse();
    }

    @Test
    @DisplayName("недекодируемые байты — техническая ошибка (не решение)")
    void недекодируемые_байты_техническая_ошибка() {
        assertThatThrownBy(() -> classifier.classify("не изображение".getBytes()))
            .isInstanceOf(ClassifierUnavailableException.class);
    }

    private byte[] image(Color color) throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, 8, 8);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
