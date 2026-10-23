package com.plantarena.media;

import com.plantarena.media.application.port.out.MediaAssetRepository;
import com.plantarena.media.domain.ImageFingerprint;
import com.plantarena.media.domain.ImageFormat;
import com.plantarena.media.domain.MediaAsset;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт MediaAssetRepository (раздел 14.2): одинаковые гарантии у in-memory
 * фейка (application-тесты) и JPA + PostgreSQL (adapter-тесты) — честность фейка.
 *
 * <p>@Transactional обязателен на самом базовом классе: аннотация из @DataJpaTest
 * на подклассе не применяется к наследуемым тест-методам, и без неё JPA-контрактные
 * тесты протекали бы в общую БД между контекстами (см. UserRepositoryContractTest).
 */
@DisplayName("Контракт MediaAssetRepository")
@Transactional
public abstract class MediaAssetRepositoryContractTest {

    protected abstract MediaAssetRepository repository();

    protected MediaAsset sampleAsset(String storageKey) {
        return MediaAsset.uploaded(UUID.randomUUID(), storageKey, ImageFormat.PNG, 123,
            8, 8, "a".repeat(64), new ImageFingerprint("b".repeat(64), 1),
            Instant.parse("2026-09-24T10:00:00Z"));
    }

    @Test
    @DisplayName("сохранение и чтение по id")
    void сохранение_и_чтение_по_id() {
        MediaAsset asset = sampleAsset("key-1.png");

        repository().save(asset);
        MediaAsset loaded = repository().findById(asset.id()).orElseThrow();

        assertThat(loaded.id()).isEqualTo(asset.id());
        assertThat(loaded.ownerId()).isEqualTo(asset.ownerId());
        assertThat(loaded.storageKey()).isEqualTo("key-1.png");
        assertThat(loaded.format()).isEqualTo(ImageFormat.PNG);
        assertThat(loaded.byteSize()).isEqualTo(123);
        assertThat(loaded.width()).isEqualTo(8);
        assertThat(loaded.height()).isEqualTo(8);
        assertThat(loaded.rawSha256()).isEqualTo("a".repeat(64));
        assertThat(loaded.fingerprint()).isEqualTo(new ImageFingerprint("b".repeat(64), 1));
        assertThat(loaded.createdAt()).isEqualTo(Instant.parse("2026-09-24T10:00:00Z"));
    }

    @Test
    @DisplayName("несуществующий id — пустой результат")
    void несуществующий_id_пустой_результат() {
        assertThat(repository().findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("удаление убирает asset")
    void удаление_убирает_asset() {
        MediaAsset asset = sampleAsset("key-2.png");
        repository().save(asset);

        repository().delete(asset.id());

        assertThat(repository().findById(asset.id())).isEmpty();
    }
}
