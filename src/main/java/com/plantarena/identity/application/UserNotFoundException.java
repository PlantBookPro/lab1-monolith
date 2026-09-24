package com.plantarena.identity.application;

import java.util.UUID;

/**
 * Пользователь не найден (переводится в HTTP 404 адаптером).
 * Выражено на едином языке и не знает об HTTP (раздел 13).
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UUID userId) {
        super("Пользователь не найден: " + userId);
    }
}
