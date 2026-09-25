package com.plantarena.plants.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Опубликованный контракт plants для tournaments (раздел 4.3): гибель
 * передаётся командой, а не записью в таблицы plants. Идемпотентна:
 * повторная гибель уже погибшего растения — no-op (рестарт без повторной
 * гибели, раздел 8).
 */
public interface PlantLifecycle {

    /**
     * Зарегистрировать гибель растения и запрет его изображения.
     *
     * @param kind             вид запрета: PERMANENT (поражение в закрытом
     *                         турнире) или COOLDOWN (поражение в глобальном, 24 ч —
     *                         политика 24 ч принадлежит tournaments)
     * @param cooldownExpiresAt обязателен для COOLDOWN, запрещён для PERMANENT
     * @param reason           причина гибели (единый язык, для истории)
     * @param sourceEntryId    участие, из которого последовала гибель (может быть null)
     */
    void registerDeath(UUID plantId, RestrictionKind kind, Instant cooldownExpiresAt,
                       String reason, UUID sourceEntryId);

    enum RestrictionKind {
        PERMANENT, COOLDOWN
    }
}
