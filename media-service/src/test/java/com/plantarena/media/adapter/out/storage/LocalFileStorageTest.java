package com.plantarena.media.adapter.out.storage;

import com.plantarena.media.domain.ImageFormat;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Локальное файловое хранилище (порт FileStorage)")
class LocalFileStorageTest {

    @TempDir
    Path root;

    private LocalFileStorage storage;

    @BeforeEach
    void создать_хранилище() {
        storage = new LocalFileStorage(root.toString());
    }

    @Test
    void сохранение_возвращает_новый_ключ_и_читается_обратно() {
        String key = storage.save(new byte[] {1, 2, 3}, ImageFormat.PNG);

        assertThat(key).endsWith(".png");
        assertThat(storage.read(key)).containsExactly(1, 2, 3);
    }

    @Test
    void повторное_сохранение_не_перезаписывает_файл() {
        String first = storage.save(new byte[] {1}, ImageFormat.PNG);
        String second = storage.save(new byte[] {2}, ImageFormat.PNG);

        assertThat(first).isNotEqualTo(second);
        assertThat(storage.read(first)).containsExactly(1);
        assertThat(storage.read(second)).containsExactly(2);
    }

    @Test
    void удаление_убирает_файл() {
        String key = storage.save(new byte[] {1}, ImageFormat.JPEG);

        storage.delete(key);

        assertThatThrownBy(() -> storage.read(key)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void чтение_несуществующего_файла_отклоняется() {
        assertThatThrownBy(() -> storage.read("missing.png"))
            .isInstanceOf(UncheckedIOException.class);
    }
}
