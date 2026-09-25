package com.plantarena.media.application;

import com.plantarena.media.application.support.FakeImageAnalyzer;
import com.plantarena.media.application.support.InMemoryFileStorage;
import com.plantarena.media.application.support.InMemoryMediaAssetRepository;
import com.plantarena.media.domain.AnalyzedImage;
import com.plantarena.media.domain.ImageFormat;
import com.plantarena.shared.security.AccessDeniedException;
import com.plantarena.shared.security.AppRole;
import com.plantarena.shared.security.CurrentActor;
import com.plantarena.shared.security.NotIdentifiedException;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Use cases media: загрузка, скачивание, удаление")
class MediaAssetServiceTest {

    private final InMemoryMediaAssetRepository repository = new InMemoryMediaAssetRepository();
    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final FakeImageAnalyzer analyzer = new FakeImageAnalyzer(
        new AnalyzedImage(ImageFormat.PNG, 2, 1, new int[] {0xFF000000, 0xFF112233}));
    private final MediaAssetService service =
        new MediaAssetService(repository, analyzer, storage, new MediaAccessPolicy(), Clock.systemUTC());

    private final UUID ownerId = UUID.randomUUID();
    private final CurrentActor owner = CurrentActor.identified(ownerId, Set.of(AppRole.USER));
    private final CurrentActor other =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER));
    private final CurrentActor admin =
        CurrentActor.identified(UUID.randomUUID(), Set.of(AppRole.USER, AppRole.ADMIN));

    @Test
    void загрузка_создаёт_asset_с_отпечатком_и_сырым_хэшем() {
        MediaAssetResult result = service.upload(owner, new byte[] {1, 2, 3});

        assertThat(result.ownerId()).isEqualTo(ownerId);
        assertThat(result.mimeType()).isEqualTo("image/png");
        assertThat(result.byteSize()).isEqualTo(3);
        assertThat(result.width()).isEqualTo(2);
        assertThat(result.height()).isEqualTo(1);
        assertThat(storage.files).hasSize(1);
        var asset = repository.assets.get(result.id());
        assertThat(asset).isNotNull();
        assertThat(asset.fingerprint().version()).isEqualTo(1);
        assertThat(asset.rawSha256()).hasSize(64);
    }

    @Test
    void файл_больше_10MiB_отклоняется_без_обращения_к_хранилищу() {
        byte[] tooLarge = new byte[(int) MediaAssetService.MAX_BYTES + 1];

        assertThatThrownBy(() -> service.upload(owner, tooLarge))
            .isInstanceOf(FileTooLargeException.class);
        assertThat(storage.files).isEmpty();
    }

    @Test
    void гость_не_может_загружать() {
        assertThatThrownBy(() -> service.upload(CurrentActor.guest(), new byte[] {1}))
            .isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    void сбой_регистрации_метаданных_удаляет_сиротский_файл() {
        repository.failureOnSave = new IllegalStateException("БД недоступна");

        assertThatThrownBy(() -> service.upload(owner, new byte[] {1, 2, 3}))
            .isInstanceOf(IllegalStateException.class);
        assertThat(storage.files).isEmpty();
        assertThat(storage.deletedKeys).hasSize(1); // компенсация (раздел 12)
    }

    @Test
    void владелец_скачивает_своё_изображение() {
        MediaAssetResult uploaded = service.upload(owner, new byte[] {1, 2, 3});

        var downloaded = service.download(owner, uploaded.id());

        assertThat(downloaded.assetId()).isEqualTo(uploaded.id());
        assertThat(downloaded.mimeType()).isEqualTo("image/png");
        assertThat(downloaded.content()).containsExactly(1, 2, 3);
    }

    @Test
    void чужой_файл_скрыт_от_другого_пользователя() {
        MediaAssetResult uploaded = service.upload(owner, new byte[] {1, 2, 3});

        assertThatThrownBy(() -> service.download(other, uploaded.id()))
            .isInstanceOf(MediaAssetNotFoundException.class); // скрыт приватностью (раздел 13)
        assertThatThrownBy(() -> service.download(CurrentActor.guest(), uploaded.id()))
            .isInstanceOf(NotIdentifiedException.class);
    }

    @Test
    void несуществующий_файл_не_найден() {
        assertThatThrownBy(() -> service.download(owner, UUID.randomUUID()))
            .isInstanceOf(MediaAssetNotFoundException.class);
    }

    @Test
    void удаление_владельцем_убирает_файл_и_метаданные() {
        MediaAssetResult uploaded = service.upload(owner, new byte[] {1, 2, 3});

        service.delete(owner, uploaded.id());

        assertThat(repository.assets).isEmpty();
        assertThat(storage.files).isEmpty();
        assertThat(storage.deletedKeys).hasSize(1);
    }

    @Test
    void чужой_пользователь_не_удаляет_файл() {
        MediaAssetResult uploaded = service.upload(owner, new byte[] {1});

        assertThatThrownBy(() -> service.delete(other, uploaded.id()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void админ_удаляет_файл() {
        MediaAssetResult uploaded = service.upload(owner, new byte[] {1});

        service.delete(admin, uploaded.id());

        assertThat(repository.assets).isEmpty();
    }
}
