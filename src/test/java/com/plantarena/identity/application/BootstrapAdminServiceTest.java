package com.plantarena.identity.application;

import com.plantarena.identity.application.support.FakePasswordHasher;
import com.plantarena.identity.application.support.InMemoryUserRepository;
import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Use case: bootstrap ADMIN из ENV (раздел 2)")
class BootstrapAdminServiceTest {

    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final BootstrapAdminService service =
        new BootstrapAdminService(users, new FakePasswordHasher());

    @Test
    void первый_запуск_создаёт_админа() {
        service.ensureAdmin("admin@example.com", "password-1", "Admin");

        User admin = users.findByEmail(new Email("admin@example.com")).orElseThrow();
        assertThat(admin.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.ADMIN);
    }

    @Test
    void повторный_запуск_не_создаёт_дубликат() {
        service.ensureAdmin("admin@example.com", "password-1", "Admin");
        service.ensureAdmin("admin@example.com", "password-2", "Admin");

        assertThat(users.count()).isOne();
    }
}
