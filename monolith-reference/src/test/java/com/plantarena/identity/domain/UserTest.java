package com.plantarena.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Агрегат User: инварианты identity (aggregates.md)")
class UserTest {

    @Test
    void у_созданного_пользователя_всегда_есть_роль_user() {
        User user = User.registerUser(new Email("alice@example.com"), "Alice", "hash-1");

        assertThat(user.roles()).containsExactly(UserRole.USER);
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void у_bootstrap_админа_роли_user_и_admin() {
        User admin = User.bootstrapAdmin(new Email("admin@example.com"), "Admin", "hash-2");

        assertThat(admin.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.ADMIN);
    }

    @Test
    void роли_меняются_только_отдельными_командами_назначить_и_снять_модератора() {
        User user = User.registerUser(new Email("bob@example.com"), "Bob", "hash-3");

        user.grantModerator();
        assertThat(user.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.MODERATOR);

        user.revokeModerator();
        assertThat(user.roles()).containsExactly(UserRole.USER);
    }

    @Test
    void снятие_модератора_не_удаляет_роль_user() {
        User user = User.registerUser(new Email("carol@example.com"), "Carol", "hash-4");

        user.revokeModerator();

        assertThat(user.roles()).containsExactly(UserRole.USER);
    }

    @Test
    void пустое_имя_профиля_отклоняется() {
        User user = User.registerUser(new Email("dave@example.com"), "Dave", "hash-5");

        assertThatThrownBy(() -> user.changeDisplayName("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void деактивация_переводит_учётную_запись_в_deactivated() {
        User user = User.registerUser(new Email("eve@example.com"), "Eve", "hash-6");

        user.deactivate();

        assertThat(user.status()).isEqualTo(UserStatus.DEACTIVATED);
    }

    @Test
    void restore_восстанавливает_все_поля_включая_версию() {
        User restored = User.restore(
            java.util.UUID.randomUUID(), new Email("restored@example.com"), "Restored", "hash-7",
            java.util.Set.of(UserRole.USER, UserRole.MODERATOR), UserStatus.DEACTIVATED,
            new GeoPoint(10, 20), 42);

        assertThat(restored.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.MODERATOR);
        assertThat(restored.status()).isEqualTo(UserStatus.DEACTIVATED);
        assertThat(restored.location()).isEqualTo(new GeoPoint(10, 20));
        assertThat(restored.version()).isEqualTo(42);
    }
}
