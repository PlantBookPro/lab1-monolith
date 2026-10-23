package com.plantarena.identity.application;

import com.plantarena.identity.application.port.in.UserAdministrationUseCase;
import com.plantarena.identity.application.support.FakePasswordHasher;
import com.plantarena.identity.application.support.InMemoryUserRepository;
import com.plantarena.identity.domain.UserRole;
import com.plantarena.identity.domain.UserStatus;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Use case: служебное управление пользователями (раздел 2)")
class UserAdministrationServiceTest {

    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final UserAdministrationService service =
        new UserAdministrationService(users, new FakePasswordHasher(), new IdentityAccessPolicy());

    private final CurrentActor admin =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER, AppRole.ADMIN));
    private final CurrentActor moderator =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER, AppRole.MODERATOR));
    private final CurrentActor plainUser =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER));

    @Test
    void модератор_создаёт_обычного_пользователя() {
        UserResult created = service.create(moderator,
            new UserAdministrationUseCase.CreateUserCommand("alice@example.com", "password-1", "Alice"));

        assertThat(created.email()).isEqualTo("alice@example.com");
        assertThat(created.roles()).containsExactly(UserRole.USER);
        assertThat(created.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void гостю_запрещено_создавать_пользователей() {
        assertThatThrownBy(() -> service.create(CurrentActor.guest(), cmd("guest@example.com")))
            .isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    void обычному_пользователю_запрещено_создавать_пользователей() {
        assertThatThrownBy(() -> service.create(plainUser, cmd("plain@example.com")))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void занятый_email_отклоняется() {
        service.create(moderator, cmd("dup@example.com"));

        assertThatThrownBy(() -> service.create(admin, cmd("dup@example.com")))
            .isInstanceOf(EmailAlreadyInUseException.class);
    }

    @Test
    void только_админ_назначает_и_снимает_модератора() {
        UserResult created = service.create(admin, cmd("mod@example.com"));

        assertThatThrownBy(() -> service.grantModerator(moderator, created.id()))
            .isInstanceOf(AccessDeniedException.class);

        UserResult granted = service.grantModerator(admin, created.id());
        assertThat(granted.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.MODERATOR);

        UserResult revoked = service.revokeModerator(admin, created.id());
        assertThat(revoked.roles()).containsExactly(UserRole.USER);
    }

    @Test
    void назначение_модератора_идемпотентно() {
        UserResult created = service.create(admin, cmd("idem@example.com"));

        service.grantModerator(admin, created.id());
        UserResult again = service.grantModerator(admin, created.id());

        assertThat(again.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.MODERATOR);
    }

    @Test
    void профиль_меняет_владелец_или_админ_но_не_другой_пользователь() {
        UserResult created = service.create(admin, cmd("victim@example.com"));

        assertThatThrownBy(() -> service.updateDisplayName(plainUser, created.id(), "Hacker"))
            .isInstanceOf(AccessDeniedException.class);

        assertThat(service.updateDisplayName(admin, created.id(), "New Name").displayName())
            .isEqualTo("New Name");
    }

    @Test
    void деактивирует_только_админ() {
        UserResult created = service.create(admin, cmd("gone@example.com"));

        assertThatThrownBy(() -> service.deactivate(moderator, created.id()))
            .isInstanceOf(AccessDeniedException.class);

        service.deactivate(admin, created.id());

        assertThat(users.findById(created.id()).orElseThrow().status())
            .isEqualTo(UserStatus.DEACTIVATED);
    }

    @Test
    void несуществующий_пользователь_не_найден() {
        assertThatThrownBy(() -> service.get(admin, UUID.randomUUID()))
            .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void список_возвращает_всех_с_количеством() {
        service.create(admin, cmd("u1@example.com"));
        service.create(admin, cmd("u2@example.com"));

        UserAdministrationUseCase.UserListResult page = service.list(moderator, 0, 20);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(2);
    }

    private UserAdministrationUseCase.CreateUserCommand cmd(String email) {
        return new UserAdministrationUseCase.CreateUserCommand(email, "password-1", "Name");
    }
}
