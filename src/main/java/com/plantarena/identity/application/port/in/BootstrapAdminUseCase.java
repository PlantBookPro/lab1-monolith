package com.plantarena.identity.application.port.in;

/**
 * Bootstrap-администратор из ENV (раздел 2): первый ADMIN создаётся при старте,
 * повторный запуск не создаёт дубликат.
 */
public interface BootstrapAdminUseCase {

    void ensureAdmin(String email, String password, String displayName);
}
