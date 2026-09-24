package com.plantarena.identity;

import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.GeoPoint;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import com.plantarena.identity.domain.UserRole;
import com.plantarena.identity.domain.UserStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт порта UserRepository (раздел 14.2): fake и JPA-адаптер ведут себя
 * одинаково. Сравнение по полям — доменные объекты не переопределяют equals.
 */
@DisplayName("Контракт UserRepository: fake и JPA ведут себя одинаково")
public abstract class UserRepositoryContractTest {

    protected abstract UserRepository repository();

    @Test
    void сохранённый_пользователь_находится_по_id_и_нормализованному_email() {
        User user = User.registerUser(new Email("Alice@Example.COM"), "Alice", "hash-1");
        repository().save(user);

        var byId = repository().findById(user.id()).orElseThrow();
        assertThat(byId.email().value()).isEqualTo("alice@example.com");
        assertThat(byId.displayName()).isEqualTo("Alice");
        assertThat(byId.passwordHash()).isEqualTo("hash-1");
        assertThat(byId.roles()).containsExactly(UserRole.USER);
        assertThat(byId.status()).isEqualTo(UserStatus.ACTIVE);

        var byEmail = repository().findByEmail(new Email("alice@example.com")).orElseThrow();
        assertThat(byEmail.id()).isEqualTo(user.id());
    }

    @Test
    void обновление_пользователя_сохраняется_роли_профиль_координаты_статус() {
        User user = User.registerUser(new Email("bob@example.com"), "Bob", "hash-2");
        repository().save(user);

        user.grantModerator();
        user.changeDisplayName("Bob II");
        user.moveTo(new GeoPoint(55.7558, 37.6173));
        repository().save(user);

        var reloaded = repository().findById(user.id()).orElseThrow();
        assertThat(reloaded.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.MODERATOR);
        assertThat(reloaded.displayName()).isEqualTo("Bob II");
        assertThat(reloaded.location()).isEqualTo(new GeoPoint(55.7558, 37.6173));
    }

    @Test
    void список_и_количество_учитывают_всех_сохранённых_пользователей() {
        for (int i = 0; i < 3; i++) {
            repository().save(User.registerUser(
                new Email("user" + i + "@example.com"), "User " + i, "hash"));
        }

        assertThat(repository().count()).isEqualTo(3);
        assertThat(repository().findAll(0, 2)).hasSize(2);
        assertThat(repository().findAll(2, 2)).hasSize(1);
    }

    @Test
    void список_упорядочен_по_id_для_стабильной_пагинации() {
        for (int i = 0; i < 5; i++) {
            repository().save(User.registerUser(
                new Email("ordered" + i + "@example.com"), "Ordered " + i, "hash"));
        }

        List<UUID> ids = new ArrayList<>();
        repository().findAll(0, 3).forEach(user -> ids.add(user.id()));
        repository().findAll(3, 3).forEach(user -> ids.add(user.id()));

        assertThat(ids).hasSize(5);
        assertThat(ids).doesNotHaveDuplicates();
        // порядок сравнения UUID как строки совпадает с побайтовым порядком
        // PostgreSQL uuid — оба наследника контракта упорядочены одинаково
        assertThat(ids).isSortedAccordingTo(Comparator.comparing(UUID::toString));
    }

    @Test
    void деактивация_и_снятие_роли_сохраняются() {
        User user = User.registerUser(new Email("carol@example.com"), "Carol", "hash-3");
        user.grantModerator();
        repository().save(user);

        user.revokeModerator();
        user.deactivate();
        repository().save(user);

        var reloaded = repository().findById(user.id()).orElseThrow();
        assertThat(reloaded.roles()).containsExactly(UserRole.USER);
        assertThat(reloaded.status()).isEqualTo(UserStatus.DEACTIVATED);
    }
}
