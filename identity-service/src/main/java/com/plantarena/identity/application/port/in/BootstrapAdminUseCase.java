package com.plantarena.identity.application.port.in;


public interface BootstrapAdminUseCase {

    void ensureAdmin(String email, String password, String displayName);
}
