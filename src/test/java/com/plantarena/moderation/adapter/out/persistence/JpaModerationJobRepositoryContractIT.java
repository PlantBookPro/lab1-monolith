package com.plantarena.moderation.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.moderation.ModerationJobRepositoryContractTest;
import com.plantarena.moderation.domain.ModerationJobRepository;
import com.plantarena.support.PostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Контракт ModerationJobRepository на JPA + PostgreSQL (Testcontainers,
 * раздел 14.2). Миграции — SchemaMigrationConfig (ADR-003), H2 не используется.
 */
@DisplayName("Контракт ModerationJobRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaModerationJobRepository.class})
class JpaModerationJobRepositoryContractIT extends ModerationJobRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaModerationJobRepository repository;

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
