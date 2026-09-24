package com.plantarena.identity.application;

import com.plantarena.identity.application.port.in.BootstrapAdminUseCase;
import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.PasswordHasher;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bootstrap-админ из ENV: идемпотентен, повторный запуск не создаёт дубликат.
 */
@Service
public class BootstrapAdminService implements BootstrapAdminUseCase {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminService.class);

    private final UserRepository users;
    private final PasswordHasher passwordHasher;

    public BootstrapAdminService(UserRepository users, PasswordHasher passwordHasher) {
        this.users = users;
        this.passwordHasher = passwordHasher;
    }

    @Override
    @Transactional
    public void ensureAdmin(String email, String password, String displayName) {
        Email adminEmail = new Email(email);
        if (users.findByEmail(adminEmail).isPresent()) {
            log.info("Bootstrap ADMIN уже существует, дубль не создаётся: {}", adminEmail.value());
            return;
        }
        users.save(User.bootstrapAdmin(adminEmail, displayName, passwordHasher.hash(password)));
        log.info("Создан bootstrap ADMIN: {}", adminEmail.value());
    }
}
