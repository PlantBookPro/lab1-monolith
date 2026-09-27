package com.plantarena.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Опубликованный контракт identity (Open Host Service, раздел 4.3): публичный
 * профиль пользователя для внутренних потребителей (tournaments — проверка
 * адресата приглашения, итерация 5). email и passwordHash не раскрываются.
 */
public interface UserDirectory {

    Optional<UserData> findById(UUID userId);

    /** Публичный профиль: id, имя, активность. */
    record UserData(UUID id, String displayName, boolean active) {
    }
}
