package com.plantarena.plants.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.plants.PlantRepositoryContractTest;
import com.plantarena.plants.domain.Plant;
import com.plantarena.plants.domain.PlantRepository;
import com.plantarena.support.PostgresSupport;
import java.util.UUID;
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
 * Контракт PlantRepository на JDBC + PostgreSQL (Testcontainers, раздел 14.2).
 * Миграции выполняет SchemaMigrationConfig (ADR-003), H2 не используется.
 * Контейнер singleton на JVM: перед каждым тестом таблицы plants чистятся
 * (внутри откатываемой транзакции @JdbcTest — данные восстанавливаются).
 */
@DisplayName("Контракт PlantRepository: JDBC + PostgreSQL (Testcontainers)")
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JdbcPlantRepository.class})
class JdbcPlantRepositoryContractIT extends PlantRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcPlantRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_растения_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from plants.plant_reservation");
        jdbcTemplate.update("delete from plants.plant");
    }

    @Override
    protected PlantRepository repository() {
        return repository;
    }

    @Test
    @DisplayName("второе активное растение на файл отклоняется БД (ADR-008)")
    void второе_активное_растение_на_файл_отклоняется_бд() {
        UUID assetId = UUID.randomUUID();
        repository().save(Plant.submit(UUID.randomUUID(), assetId, fingerprint('7'),
            "Первое", NOW));

        assertThatThrownBy(() -> repository().save(Plant.submit(UUID.randomUUID(), assetId,
            fingerprint('8'), "Второе", NOW)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
