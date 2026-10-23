package com.plantarena.moderation.support;

import com.plantarena.moderation.application.port.out.ClassifierUnavailableException;
import com.plantarena.moderation.application.port.out.PlantClassifier;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Детерминированный классификатор для тестов/демо (без сети и модели):
 * изображение с доминирующим зелёным каналом — растение. Используется в
 * ModerationApiIT через @TestConfiguration (спека итерации 4).
 */
public class DeterministicPlantClassifier implements PlantClassifier {

    public static final String MODEL_VERSION = "deterministic-green-v1";

    @Override
    public Classification classify(byte[] imageBytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException e) {
            throw new ClassifierUnavailableException("Не удалось декодировать изображение", e);
        }
        if (image == null) {
            throw new ClassifierUnavailableException("Не удалось декодировать изображение");
        }
        long red = 0;
        long green = 0;
        long blue = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                red += (rgb >> 16) & 0xFF;
                green += (rgb >> 8) & 0xFF;
                blue += rgb & 0xFF;
            }
        }
        boolean plant = green > red && green > blue;
        return new Classification(plant, 0.9f, MODEL_VERSION);
    }
}
