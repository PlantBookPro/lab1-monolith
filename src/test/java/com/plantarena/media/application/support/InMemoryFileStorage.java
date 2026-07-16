package com.plantarena.media.application.support;

import com.plantarena.media.domain.FileStorage;
import com.plantarena.media.domain.ImageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory хранилище для application-тестов: помнит сохранённое и удалённое. */
public class InMemoryFileStorage implements FileStorage {

    public final Map<String, byte[]> files = new ConcurrentHashMap<>();
    public final List<String> deletedKeys = new ArrayList<>();

    @Override
    public String save(byte[] content, ImageFormat format) {
        String storageKey = UUID.randomUUID() + "." + format.extension();
        files.put(storageKey, content);
        return storageKey;
    }

    @Override
    public byte[] read(String storageKey) {
        byte[] content = files.get(storageKey);
        if (content == null) {
            throw new IllegalStateException("Файл не найден в хранилище: " + storageKey);
        }
        return content;
    }

    @Override
    public void delete(String storageKey) {
        files.remove(storageKey);
        deletedKeys.add(storageKey);
    }
}
