package com.plantarena.identity.adapter.in.jobs;

import com.plantarena.identity.application.port.in.BootstrapAdminUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Bootstrap-админ из ENV (раздел 2): первый ADMIN создаётся при старте,
 * повторный запуск не создаёт дубликат (идемпотентность — в use case).
 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final BootstrapAdminUseCase bootstrapAdmin;
    private final String email;
    private final String password;
    private final String displayName;

    public BootstrapAdminRunner(BootstrapAdminUseCase bootstrapAdmin,
                                @Value("${plantarena.bootstrap-admin.email:}") String email,
                                @Value("${plantarena.bootstrap-admin.password:}") String password,
                                @Value("${plantarena.bootstrap-admin.display-name:Admin}") String displayName) {
        this.bootstrapAdmin = bootstrapAdmin;
        this.email = email;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank()) {
            log.info("Bootstrap ADMIN не настроен (BOOTSTRAP_ADMIN_EMAIL пуст) — пропускается");
            return;
        }
        if (password.isBlank()) {
            throw new IllegalStateException(
                "BOOTSTRAP_ADMIN_PASSWORD обязателен, если задан BOOTSTRAP_ADMIN_EMAIL");
        }
        bootstrapAdmin.ensureAdmin(email, password, displayName);
    }
}
