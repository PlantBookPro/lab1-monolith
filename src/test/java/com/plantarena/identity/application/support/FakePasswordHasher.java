package com.plantarena.identity.application.support;

import com.plantarena.identity.domain.PasswordHasher;

/**
 * Детерминированный fake хэширования паролей — только для тестов.
 */
public class FakePasswordHasher implements PasswordHasher {

    @Override
    public String hash(String rawPassword) {
        return "fake:" + rawPassword;
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return passwordHash.equals("fake:" + rawPassword);
    }
}
