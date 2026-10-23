package com.plantarena.identity.application;

import com.plantarena.identity.application.support.InMemoryUserRepository;
import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.User;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Use case: идентификация субъекта по userId (ADR-005)")
class ResolveActorServiceTest {

    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final ResolveActorService service = new ResolveActorService(users);

    @Test
    void активный_пользователь_резолвится_в_свои_роли() {
        User admin = users.save(User.bootstrapAdmin(new Email("admin@example.com"), "Admin", "hash"));

        CurrentActor actor = service.resolve(admin.id());

        assertThat(actor.isGuest()).isFalse();
        assertThat(actor.userId()).isEqualTo(admin.id());
        assertThat(actor.hasRole(AppRole.ADMIN)).isTrue();
        assertThat(actor.hasRole(AppRole.USER)).isTrue();
    }

    @Test
    void неизвестный_id_отклоняется() {
        assertThatThrownBy(() -> service.resolve(UUID.randomUUID()))
            .isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    void деактивированный_id_отклоняется() {
        User user = users.save(User.registerUser(new Email("off@example.com"), "Off", "hash"));
        user.deactivate();
        users.save(user);

        assertThatThrownBy(() -> service.resolve(user.id()))
            .isInstanceOf(NotIdentifiedException.class);
    }
}
