package com.plantarena.media.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Агрегат media (раздел 6): неизменяемый загруженный файл и его метаданные.
 * Инвариант: неизменяем после создания — мутаторов нет; повторная загрузка
 * создаёт новый asset. storageKey — внутренний, наружу не публикуется.
 */
public final class MediaAsset {

    private final UUID id;
    private final UUID ownerId;
    private final String storageKey;
    private final ImageFormat format;
    private final long byteSize;
    private final int width;
    private final int height;
    private final String rawSha256;
    private final ImageFingerprint fingerprint;
    private final Instant createdAt;

    private MediaAsset(UUID id, UUID ownerId, String storageKey, ImageFormat format,
                       long byteSize, int width, int height, String rawSha256,
                       ImageFingerprint fingerprint, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.storageKey = Objects.requireNonNull(storageKey, "storageKey");
        this.format = Objects.requireNonNull(format, "format");
        if (byteSize <= 0) {
            throw new IllegalArgumentException("byteSize должен быть положительным");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Размеры изображения должны быть положительными");
        }
        this.byteSize = byteSize;
        this.width = width;
        this.height = height;
        this.rawSha256 = Objects.requireNonNull(rawSha256, "rawSha256");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static MediaAsset uploaded(UUID ownerId, String storageKey, ImageFormat format,
                                      long byteSize, int width, int height, String rawSha256,
                                      ImageFingerprint fingerprint, Instant createdAt) {
        return new MediaAsset(UUID.randomUUID(), ownerId, storageKey, format, byteSize,
            width, height, rawSha256, fingerprint, createdAt);
    }

    /** Восстановление из хранилища с сохранением id (JPA-адаптер). */
    public static MediaAsset restore(UUID id, UUID ownerId, String storageKey, ImageFormat format,
                                     long byteSize, int width, int height, String rawSha256,
                                     ImageFingerprint fingerprint, Instant createdAt) {
        return new MediaAsset(id, ownerId, storageKey, format, byteSize,
            width, height, rawSha256, fingerprint, createdAt);
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public String storageKey() {
        return storageKey;
    }

    public ImageFormat format() {
        return format;
    }

    public long byteSize() {
        return byteSize;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public String rawSha256() {
        return rawSha256;
    }

    public ImageFingerprint fingerprint() {
        return fingerprint;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
