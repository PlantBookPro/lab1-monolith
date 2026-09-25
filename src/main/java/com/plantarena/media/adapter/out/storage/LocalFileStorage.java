package com.plantarena.media.adapter.out.storage;

import com.plantarena.media.domain.FileStorage;
import com.plantarena.media.domain.ImageFormat;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Локальное файловое хранилище за портом FileStorage (раздел 6): Docker volume
 * в лабе №1; в лабе №4 заменяется на file-service с S3-совместимым хранилищем —
 * меняется только этот адаптер. Ключи генерирует хранилище (UUID + расширение),
 * перезапись невозможна (CREATE_NEW). Ключи системные — наружу не публикуются.
 */
@Component
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(@Value("${plantarena.media.storage.root}") String root) {
        this.root = Path.of(root);
    }

    @Override
    public String save(byte[] content, ImageFormat format) {
        String storageKey = UUID.randomUUID() + "." + format.extension();
        try {
            Files.createDirectories(root);
            Files.write(root.resolve(storageKey), content,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return storageKey;
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить файл в хранилище", e);
        }
    }

    @Override
    public byte[] read(String storageKey) {
        try {
            return Files.readAllBytes(root.resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Файл не найден в хранилище: " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(root.resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось удалить файл из хранилища", e);
        }
    }
}
