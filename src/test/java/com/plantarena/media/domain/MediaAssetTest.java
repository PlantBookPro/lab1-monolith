package com.plantarena.media.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MediaAsset: неизменяемый загруженный файл")
class MediaAssetTest {

    private MediaAsset asset() {
        return MediaAsset.uploaded(UUID.randomUUID(), "key.png", ImageFormat.PNG, 123,
            8, 8, "a".repeat(64), new ImageFingerprint("b".repeat(64), 1),
            Instant.parse("2026-09-24T10:00:00Z"));
    }

    @Test
    void повторная_загрузка_создаёт_новый_asset() {
        MediaAsset first = asset();
        MediaAsset second = asset();

        assertThat(first.id()).isNotEqualTo(second.id()); // перезапись невозможна (раздел 6)
    }

    @Test
    void восстановление_из_хранилища_сохраняет_id() {
        UUID id = UUID.randomUUID();

        MediaAsset restored = MediaAsset.restore(id, UUID.randomUUID(), "key.png",
            ImageFormat.PNG, 123, 8, 8, "a".repeat(64),
            new ImageFingerprint("b".repeat(64), 1), Instant.parse("2026-09-24T10:00:00Z"));

        assertThat(restored.id()).isEqualTo(id);
    }

    @Test
    void метаданные_доступны_только_для_чтения() {
        MediaAsset asset = asset();

        assertThat(asset.ownerId()).isNotNull();
        assertThat(asset.storageKey()).isEqualTo("key.png");
        assertThat(asset.format()).isEqualTo(ImageFormat.PNG);
        assertThat(asset.byteSize()).isEqualTo(123);
        assertThat(asset.width()).isEqualTo(8);
        assertThat(asset.height()).isEqualTo(8);
        assertThat(asset.rawSha256()).isEqualTo("a".repeat(64));
        assertThat(asset.fingerprint()).isEqualTo(new ImageFingerprint("b".repeat(64), 1));
        assertThat(asset.createdAt()).isEqualTo(Instant.parse("2026-09-24T10:00:00Z"));
    }

    @Test
    void некорректные_метаданные_отклоняются() {
        assertThatThrownBy(() -> MediaAsset.uploaded(UUID.randomUUID(), "k", ImageFormat.PNG, 0,
            8, 8, "a".repeat(64), new ImageFingerprint("b".repeat(64), 1), Instant.now()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaAsset.uploaded(UUID.randomUUID(), "k", ImageFormat.PNG, 10,
            0, 8, "a".repeat(64), new ImageFingerprint("b".repeat(64), 1), Instant.now()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaAsset.uploaded(UUID.randomUUID(), "k", ImageFormat.PNG, 10,
            8, 8, "a".repeat(64), null, Instant.now()))
            .isInstanceOf(NullPointerException.class);
    }
}
