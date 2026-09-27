package com.plantarena.geo.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Участник кластеризации (раздел 8): глобальное участие + координаты
 * владельца на момент фиксации эпохи (locationVersion — версия профиля).
 */
public record ClusterMember(UUID entryId, UUID userId, double latitude, double longitude,
                            long locationVersion) {

    public ClusterMember {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(userId, "userId");
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Широта вне диапазона [-90, 90]: " + latitude);
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Долгота вне диапазона [-180, 180]: " + longitude);
        }
    }
}
