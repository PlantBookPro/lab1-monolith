package com.plantarena.identity.application;

import com.plantarena.identity.application.support.InMemoryUserRepository;
import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRole;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Use case: собственный профиль (/me)")
class MyProfileServiceTest {

    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final MyProfileService service = new MyProfileService(users, new IdentityAccessPolicy());

    @Test
    void пользователь_видит_свой_профиль() {
        User user = users.save(User.registerUser(new Email("me@example.com"), "Me", "hash"));

        UserResult result = service.me(CurrentActor.identified(user.id(), Set.of(AppRole.USER)));

        assertThat(result.displayName()).isEqualTo("Me");
        assertThat(result.roles()).containsExactly(UserRole.USER);
    }

    @Test
    void гость_не_имеет_профиля() {
        assertThatThrownBy(() -> service.me(CurrentActor.guest()))
            .isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    void пользователь_обновляет_свои_координаты() {
        User user = users.save(User.registerUser(new Email("geo@example.com"), "Geo", "hash"));

        UserResult updated = service.updateLocation(
            CurrentActor.identified(user.id(), Set.of(AppRole.USER)), 55.7558, 37.6173);

        assertThat(updated.latitude()).isEqualTo(55.7558);
        assertThat(updated.longitude()).isEqualTo(37.6173);
    }

    @Test
    void координаты_вне_диапазона_отклоняются_доменом() {
        User user = users.save(User.registerUser(new Email("bad@example.com"), "Bad", "hash"));

        assertThatThrownBy(() -> service.updateLocation(
            CurrentActor.identified(user.id(), Set.of(AppRole.USER)), 91, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
