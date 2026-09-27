package com.plantarena.moderation.application.port.out;

/**
 * Порт классификатора растений (ADR-009): байты изображения → решение.
 * Потребитель (moderation) владеет типами; реализации — адаптеры
 * (OnnxPlantClassifier в основном профиле, DeterministicPlantClassifier в тестах).
 */
public interface PlantClassifier {

    Classification classify(byte[] imageBytes);

    /**
     * @param plant распознано растение (top-1 ∈ растительные классы, confidence ≥ порога)
     * @param confidence уверенность top-1 после softmax, [0..1]
     * @param modelVersion версия модели + списка классов (наблюдаемость)
     */
    record Classification(boolean plant, float confidence, String modelVersion) {
    }
}
