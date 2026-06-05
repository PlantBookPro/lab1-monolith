package com.plantarena.tournaments.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Выходной порт tournaments: гибель растения и запрет его изображения при
 * выбывании (разделы 6, 12.3, Consumer-driven). Адаптер
 * tournaments.adapter.out.plants вызывает plants.api.PlantLifecycle (ACL,
 * раздел 4.3): гибель передаётся командой, а не записью в таблицы plants.
 * Вид запрета выбирает tournaments: PERMANENT — поражение в закрытом
 * турнире, COOLDOWN — в глобальном (итерация 7).
 */
public interface PlantLifecycleGateway {

    /**
     * Зарегистрировать гибель и запрет.
     *
     * @param kind             PERMANENT (без expiresAt) или COOLDOWN (24 ч — итерация 7)
     * @param cooldownExpiresAt обязателен для COOLDOWN, запрещён для PERMANENT
     * @param reason           причина в едином языке (для истории)
     * @param sourceEntryId    участие, из которого последовала гибель
     */
    void registerDeath(UUID plantId, RestrictionKind kind, Instant cooldownExpiresAt,
                       String reason, UUID sourceEntryId);

    enum RestrictionKind {
        PERMANENT, COOLDOWN
    }
}
