package com.plantarena.media.adapter.out.persistence;

import com.plantarena.config.SchemaMigrationConfig;
import com.plantarena.media.domain.AssetClaim;
import com.plantarena.support.PostgresSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт AssetClaimRepository на JPA + PostgreSQL (Testcontainers, раздел 14.2).
 * Миграции выполняет SchemaMigrationConfig (ADR-003), H2 не используется.
 * Контейнер singleton на JVM: перед каждым тестом таблицы чистятся
 * (внутри откатываемой транзакции @DataJpaTest).
 */
@DisplayName("Контракт AssetClaimRepository: JPA + PostgreSQL (Testcontainers)")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMigrationConfig.class, JpaAssetClaimRepository.class})
class JpaAssetClaimRepositoryIT {

    @DynamicPropertySource
    static void testcontainersPostgres(DynamicPropertyRegistry registry) {
        var postgres = PostgresSupport.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JpaAssetClaimRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void очистить_задействованность_от_предыдущих_контекстов() {
        jdbcTemplate.update("delete from media.asset_claim");
        jdbcTemplate.update("delete from media.media_asset");
    }

    private UUID insertedAsset() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            insert into media.media_asset (id, owner_id, storage_key, mime_type, byte_size,
                width, height, raw_sha256, image_fingerprint, fingerprint_version, created_at)
            values (?, ?, ?, 'image/png', 3, 2, 1, ?, ?, 1, now())
            """, id, UUID.randomUUID(), "claim-it-" + id + ".png",
            "a".repeat(64), "b".repeat(64));
        return id;
    }

    @Test
    @DisplayName("задействованность сохраняется, находится по файлу и удаляется")
    void задействованность_сохраняется_находится_по_файлу_и_удаляется() {
        UUID assetId = insertedAsset();
        UUID plantId = UUID.randomUUID();
        repository.save(AssetClaim.claimed(assetId, plantId, true,
            Instant.parse("2026-09-25T10:00:00Z")));

        var found = repository.findByAssetId(assetId);

        assertThat(found).isPresent();
        assertThat(found.get().plantId()).isEqualTo(plantId);
        assertThat(found.get().publiclyVisible()).isTrue();

        repository.deleteByAssetId(assetId);
        assertThat(repository.findByAssetId(assetId)).isEmpty();
    }

    @Test
    @DisplayName("повторный claim того же файла перезаписывает задействованность (PK asset_id)")
    void повторный_claim_того_же_файла_перезаписывает_задействованность() {
        UUID assetId = insertedAsset();
        repository.save(AssetClaim.claimed(assetId, UUID.randomUUID(), false,
            Instant.parse("2026-09-25T10:00:00Z")));

        repository.save(AssetClaim.claimed(assetId, UUID.randomUUID(), true,
            Instant.parse("2026-09-25T11:00:00Z"))); // merge → update по PK

        var found = repository.findByAssetId(assetId).orElseThrow();
        assertThat(found.publiclyVisible()).isTrue();
    }
}
