package com.plantarena.geo.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.geo.ClusterSnapshotRepositoryContractTest;
import com.plantarena.geo.domain.ClusterSnapshotRepository;
import com.plantarena.support.PostgresSupport;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Контракт JPA-адаптера на Testcontainers PostgreSQL (раздел 14.2). Миграции
 * выполняет SchemaMigrationConfig (ADR-003); аннотации — по образцу
 * JpaVotingWindowRepositoryContractIT.
 */
@DisplayName("Контракт ClusterSnapshotRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaClusterSnapshotRepository.class})
class JpaClusterSnapshotRepositoryContractIT extends ClusterSnapshotRepositoryContractTest {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaClusterSnapshotRepository repository;

    @Override
    protected ClusterSnapshotRepository repository() {
        return repository;
    }
}
