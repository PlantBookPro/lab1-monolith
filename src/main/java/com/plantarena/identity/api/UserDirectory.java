package com.plantarena.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Опубликованный контракт identity (Open Host Service, раздел 4.3): публичный
 * профиль и координаты для внутренних потребителей (tournaments — гео-
 * кластеризация, итерация 7). email и passwordHash не раскрываются; точные
 * координаты наружу через REST не публикуются — только через этот контракт.
 */
public interface UserDirectory {

    Optional<UserData> findById(UUID userId);

    /** Координаты профиля и версия (locationVersion) на момент чтения. */
    Optional<UserLocation> findLocation(UUID userId);

    /** Публичный профиль: id, имя, активность. */
    record UserData(UUID id, String displayName, boolean active) {
    }

    /** Координаты профиля (раздел 8: для глобального участия обязательны). */
    record UserLocation(double latitude, double longitude, long locationVersion) {
    }
}
