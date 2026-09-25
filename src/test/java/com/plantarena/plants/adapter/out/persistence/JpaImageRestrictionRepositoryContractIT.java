package com.plantarena.plants.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.plants.ImageRestrictionRepositoryContractTest;
import com.plantarena.plants.domain.ImageRestrictionRepository;
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
 * Контракт ImageRestrictionRepository на JPA + PostgreSQL (Testcontainers,
 * раздел 14.2). Миграции выполняет SchemaMigrationConfig (ADR-003).
 */
@DisplayName("Контракт ImageRestrictionRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaImageRestrictionRepository.class})
class JpaImageRestrictionRepositoryContractIT extends ImageRestrictionRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaImageRestrictionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_запреты_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from plants.image_restriction");
    }

    @Override
    protected ImageRestrictionRepository repository() {
        return repository;
    }
}
