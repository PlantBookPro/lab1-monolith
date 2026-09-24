package com.plantarena.identity.domain;

/**
 * Порт хэширования паролей: домен не знает о spring-security-crypto (раздел 2).
 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}
