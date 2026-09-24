package com.plantarena.identity.adapter.out.crypto;

import com.plantarena.identity.domain.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Адаптер порта PasswordHasher на spring-security-crypto (раздел 2 требований):
 * домен не знает о реализации хэширования.
 */
@Component
public class SpringSecurityPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return encoder.matches(rawPassword, passwordHash);
    }
}
