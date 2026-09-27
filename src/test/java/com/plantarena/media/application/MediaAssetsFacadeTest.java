package com.plantarena.media.application;

import com.plantarena.media.api.MediaContent;
import com.plantarena.media.application.support.InMemoryFileStorage;
import com.plantarena.media.application.support.InMemoryMediaAssetRepository;
import com.plantarena.media.domain.ImageFingerprint;
import com.plantarena.media.domain.ImageFormat;
import com.plantarena.media.domain.MediaAsset;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Контракт MediaAssets.loadContent: байты и mimeType, storageKey не покидает media")
class MediaAssetsFacadeTest {

    private final InMemoryMediaAssetRepository repository = new InMemoryMediaAssetRepository();
    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final MediaAssetsFacade facade = new MediaAssetsFacade(repository, storage);

    private UUID storedAsset(byte[] content, String mimeType) {
        ImageFormat format = ImageFormat.fromMimeType(mimeType);
        String storageKey = storage.save(content, format);
        MediaAsset asset = MediaAsset.uploaded(UUID.randomUUID(), storageKey, format,
            content.length, 8, 8, "sha256",
            new ImageFingerprint("f".repeat(64), 1), Instant.parse("2026-09-27T10:00:00Z"));
        repository.save(asset);
        return asset.id();
    }

    @Test
    @DisplayName("loadContent возвращает сохранённые байты и mimeType")
    void loadContent_возвращает_байты_и_mime_тип() {
        byte[] content = new byte[] {1, 2, 3};
        UUID assetId = storedAsset(content, "image/png");

        MediaContent loaded = facade.loadContent(assetId).orElseThrow();

        assertThat(loaded.content()).containsExactly(content);
        assertThat(loaded.mimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("неизвестный id — пустой результат")
    void loadContent_неизвестный_id_пустой_результат() {
        assertThat(facade.loadContent(UUID.randomUUID())).isEmpty();
    }
}
