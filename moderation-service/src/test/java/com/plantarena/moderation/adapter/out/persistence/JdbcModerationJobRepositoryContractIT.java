package com.plantarena.moderation.adapter.out.persistence;

import com.plantarena.moderation.ModerationJobRepositoryContractTest;
import com.plantarena.moderation.domain.ModerationJobRepository;
import com.plantarena.support.PostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Контракт ModerationJobRepository на JDBC + PostgreSQL (Testcontainers,
 * раздел 14.2). Миграции выполняет Flyway (spring.flyway.*, application-test.yml),
 * H2 не используется.
 */
@DisplayName("Контракт ModerationJobRepository: JDBC + PostgreSQL (Testcontainers)")
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JdbcModerationJobRepository.class)
class JdbcModerationJobRepositoryContractIT extends ModerationJobRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcModerationJobRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_задания_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from moderation.moderation_job");
    }

    @Override
    protected ModerationJobRepository repository() {
        return repository;
    }
}
