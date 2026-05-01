package com.plantarena.shared.security;

/**
 * Идентификация отсутствует или невалидна для защищённой ручки (HTTP 401).
 * Техническое исключение, не доменное.
 */
public class NotIdentifiedException extends RuntimeException {

    public NotIdentifiedException(String message) {
        super(message);
    }
}
