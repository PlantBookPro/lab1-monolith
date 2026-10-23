package com.plantarena.geo.adapter.out.persistence;

import com.plantarena.geo.domain.ClusterMember;
import com.plantarena.geo.domain.ClusterSnapshot;
import com.plantarena.support.PostgresSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт R2DBC-адаптера на Testcontainers PostgreSQL. Миграции выполняет
 * Flyway по JDBC-каналу (spring.flyway.url, DataSourceAutoConfiguration
 * исключён — Flyway строит собственный DataSource), данные ходят по R2DBC.
 */
@DisplayName("Контракт ClusterSnapshotRepository: R2DBC + PostgreSQL (Testcontainers)")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class R2dbcClusterSnapshotRepositoryIT {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.r2dbc.url",
            () -> "r2dbc:postgresql://%s:%d/geo".formatted(postgres.getHost(), postgres.getFirstMappedPort()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    private R2dbcClusterSnapshotRepository repository;

    @Test
    @DisplayName("save → findByEpochId возвращает снимки эпохи; findById — по id с составом")
    void сохранение_и_чтение() {
        UUID epochId = UUID.randomUUID();
        ClusterSnapshot first = snapshot(epochId, "u4pu", "e-1");
        ClusterSnapshot second = snapshot(epochId, "u8t", "e-2");

        StepVerifier.create(repository.save(first).then(repository.save(second)))
            .expectNext(second)
            .verifyComplete();

        StepVerifier.create(repository.findByEpochId(epochId))
            .assertNext(snapshot -> assertThat(snapshot.clusterKey()).isEqualTo("u4pu"))
            .assertNext(snapshot -> assertThat(snapshot.clusterKey()).isEqualTo("u8t"))
            .verifyComplete();

        StepVerifier.create(repository.findById(first.id()))
            .assertNext(loaded -> {
                assertThat(loaded.policyVersion()).isEqualTo("geohash-v1-p4");
                assertThat(loaded.members()).isEqualTo(first.members());
            })
            .verifyComplete();

        StepVerifier.create(repository.findById(UUID.randomUUID()))
            .verifyComplete();
    }

    @Test
    @DisplayName("многокомпонентный кластер: состав round-trip без потерь, порядок по entry_id")
    void многокомпонентный_состав() {
        UUID epochId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ClusterSnapshot multi = ClusterSnapshot.fix(epochId, "ucfv", "geohash-v1-p4",
            List.of(
                new ClusterMember(UUID.nameUUIDFromBytes("e-1".getBytes()), userId, 55.75, 37.61, 1L),
                new ClusterMember(UUID.nameUUIDFromBytes("e-2".getBytes()), userId, 55.76, 37.62, 2L),
                new ClusterMember(UUID.nameUUIDFromBytes("e-3".getBytes()), userId, 55.77, 37.63, 3L)),
            Instant.parse("2026-09-27T10:00:00Z"));

        StepVerifier.create(repository.save(multi)).expectNext(multi).verifyComplete();

        StepVerifier.create(repository.findById(multi.id()))
            .assertNext(loaded -> assertThat(loaded.members()).containsExactly(
                new ClusterSnapshot.SnapshotMember(userId,
                    UUID.nameUUIDFromBytes("e-1".getBytes()), 1L),
                new ClusterSnapshot.SnapshotMember(userId,
                    UUID.nameUUIDFromBytes("e-2".getBytes()), 2L),
                new ClusterSnapshot.SnapshotMember(userId,
                    UUID.nameUUIDFromBytes("e-3".getBytes()), 3L)))
            .verifyComplete();
    }

    private ClusterSnapshot snapshot(UUID epochId, String clusterKey, String entrySuffix) {
        UUID entryId = UUID.nameUUIDFromBytes(entrySuffix.getBytes());
        return ClusterSnapshot.fix(epochId, clusterKey, "geohash-v1-p4",
            List.of(new ClusterMember(entryId, UUID.randomUUID(), 55.7558, 37.6173, 1L)),
            Instant.parse("2026-09-27T10:00:00Z"));
    }
}
