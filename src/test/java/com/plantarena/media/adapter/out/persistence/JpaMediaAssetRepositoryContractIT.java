package com.plantarena.media.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.media.MediaAssetRepositoryContractTest;
import com.plantarena.media.application.port.out.MediaAssetRepository;
import com.plantarena.media.domain.MediaAsset;
import com.plantarena.support.PostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Контракт MediaAssetRepository на JPA + PostgreSQL (Testcontainers, раздел 14.2).
 * Миграции выполняет SchemaMigrationConfig (ADR-003), H2 не используется.
 * Контейнер singleton на JVM: предыдущие @SpringBootTest-контексты коммитят
 * asset'ы в общую БД, поэтому перед каждым тестом таблица чистится
 * (внутри откатываемой транзакции @DataJpaTest — данные восстанавливаются).
 */
@DisplayName("Контракт MediaAssetRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaMediaAssetRepository.class})
class JpaMediaAssetRepositoryContractIT extends MediaAssetRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaMediaAssetRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_файлы_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from media.media_asset");
    }

    @Override
    protected MediaAssetRepository repository() {
        return repository;
    }

    @Test
    @DisplayName("дубликат storage_key отклоняется ограничением БД (UNIQUE)")
    void дубликат_storage_key_отклоняется_ограничением_бд() {
        repository().save(sampleAsset("same-key.png"));
        MediaAsset duplicate = sampleAsset("same-key.png");

        assertThatThrownBy(() -> repository().save(duplicate))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
