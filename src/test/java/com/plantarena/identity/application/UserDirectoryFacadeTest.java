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
}
