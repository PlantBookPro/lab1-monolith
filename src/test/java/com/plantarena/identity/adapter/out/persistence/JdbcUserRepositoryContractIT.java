package com.plantarena.identity.adapter.out.persistence;

import com.plantarena.identity.UserRepositoryContractTest;
import com.plantarena.identity.domain.Email;
import com.plantarena.identity.domain.User;
import com.plantarena.identity.domain.UserRepository;
import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.support.PostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Контракт UserRepository на JDBC + PostgreSQL (Testcontainers, раздел 14.2).
 * Миграции выполняет SchemaMigrationConfig (ADR-003), H2 не используется.
 * Контейнер singleton на JVM: предыдущие @SpringBootTest-контексты коммитят
 * bootstrap-админа в общую БД, поэтому перед каждым тестом таблицы чистятся
 * (внутри откатываемой транзакции @JdbcTest — данные восстанавливаются).
 */
@DisplayName("Контракт UserRepository: JDBC + PostgreSQL (Testcontainers)")
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JdbcUserRepository.class})
class JdbcUserRepositoryContractIT extends UserRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcUserRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_пользователей_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from identity.user_role");
        jdbcTemplate.update("delete from identity.app_user");
    }

    @Override
    protected UserRepository repository() {
        return repository;
    }

    @Test
    @DisplayName("дубликат email отклоняется ограничением БД (UNIQUE)")
    void дубликат_email_отклоняется_ограничением_бд() {
        repository().save(User.registerUser(new Email("dup@example.com"), "First", "hash"));
        User duplicate = User.registerUser(new Email("dup@example.com"), "Second", "hash");

        assertThatThrownBy(() -> repository().save(duplicate))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
