package com.plantarena.plants.api;

import java.util.UUID;

/**
 * Опубликованный контракт plants для moderation (раздел 4.3): решение
 * передаётся командой, а не записью в таблицы plants. Реализация — в
 * plants.application (правило 10.2.4).
 */
public interface PlantModeration {

    /**
     * Зафиксировать решение модерации по заявке.
     * Идемпотентно: повтор того же решения — no-op; конфликтующее решение
     * по уже решённой заявке — {@link ModerationAlreadyDecidedException}.
     */
    PlantData recordDecision(UUID plantId, Decision decision, String reason);

    enum Decision {
        APPROVED, REJECTED
    }
}
