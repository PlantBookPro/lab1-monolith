package com.plantarena.identity.application;

import com.plantarena.identity.api.UserDirectory;
import com.plantarena.identity.application.support.InMemoryUserRepository;
import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.User;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Контракт UserDirectory: публичный профиль для внутреннего потребителя (tournaments)")
class UserDirectoryFacadeTest {

    private final InMemoryUserRepository repository = new InMemoryUserRepository();
    private final UserDirectoryFacade facade = new UserDirectoryFacade(repository);

    @Test
    @DisplayName("findById возвращает id, имя и активность без email и passwordHash")
    void find_by_id_возвращает_публичный_профиль() {
        User user = User.registerUser(new Email("u@example.com"), "U", "hash");
        repository.save(user);

        UserDirectory.UserData data = facade.findById(user.id()).orElseThrow();

        assertThat(data.id()).isEqualTo(user.id());
        assertThat(data.displayName()).isEqualTo("U");
        assertThat(data.active()).isTrue();
    }

    @Test
    @DisplayName("неизвестный id — пустой результат")
    void неизвестный_id_пустой_результат() {
        assertThat(facade.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("findLocation: координаты и версия профиля; без координат — пусто")
    void find_location_координаты_и_версия() {
        User located = User.registerUser(new Email("geo@example.com"), "Geo", "hash");
        located.moveTo(new com.plantarena.identity.domain.GeoPoint(55.7558, 37.6173));
        repository.save(located);
        User plain = User.registerUser(new Email("plain@example.com"), "Plain", "hash");
        repository.save(plain);

        UserDirectory.UserLocation location = facade.findLocation(located.id()).orElseThrow();
        assertThat(location.latitude()).isEqualTo(55.7558);
        assertThat(location.longitude()).isEqualTo(37.6173);
        assertThat(location.locationVersion()).isEqualTo(located.version());
        assertThat(facade.findLocation(plain.id())).isEmpty();
        assertThat(facade.findLocation(UUID.randomUUID())).isEmpty();
    }
}
