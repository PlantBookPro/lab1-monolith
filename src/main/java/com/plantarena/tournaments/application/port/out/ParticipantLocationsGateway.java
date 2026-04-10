package com.plantarena.tournaments.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Выходной порт tournaments: координаты пользователя для гео-кластеризации
 * (раздел 8). Адаптер — ACL над identity.api.UserDirectory (OHS, раздел 4.3);
 * наружу точные координаты не публикуются — только внутренним потребителям.
 */
public interface ParticipantLocationsGateway {

    /** Координаты профиля (null-профиля нет — Optional пуст). */
    Optional<UserLocation> findLocation(UUID userId);

    /** Координаты и версия профиля на момент чтения (locationVersion). */
    record UserLocation(double latitude, double longitude, long locationVersion) {
    }
}
