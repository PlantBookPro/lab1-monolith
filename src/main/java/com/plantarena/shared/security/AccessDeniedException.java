package com.plantarena.shared.security;

/**
 * Идентифицированный субъект не имеет права на действие (HTTP 403).
 * Техническое исключение, не доменное.
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
