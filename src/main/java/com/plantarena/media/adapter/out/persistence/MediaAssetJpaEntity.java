package com.plantarena.media.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA-модель media_asset (раздел 11); маппинг на домен — явный (MediaAssetMapper).
 * Агрегат неизменяем — entity только создаётся и читается.
 */
@Entity
@Table(name = "media_asset", schema = "media")
public class MediaAssetJpaEntity {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "width", nullable = false)
    private int width;

    @Column(name = "height", nullable = false)
    private int height;

    @Column(name = "raw_sha256", nullable = false, length = 64)
    private String rawSha256;

    @Column(name = "image_fingerprint", nullable = false, length = 64)
    private String imageFingerprint;

    @Column(name = "fingerprint_version", nullable = false)
    private int fingerprintVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MediaAssetJpaEntity() {
    }

    MediaAssetJpaEntity(UUID id, UUID ownerId, String storageKey, String mimeType, long byteSize,
                        int width, int height, String rawSha256, String imageFingerprint,
                        int fingerprintVersion, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.storageKey = storageKey;
        this.mimeType = mimeType;
        this.byteSize = byteSize;
        this.width = width;
        this.height = height;
        this.rawSha256 = rawSha256;
        this.imageFingerprint = imageFingerprint;
        this.fingerprintVersion = fingerprintVersion;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getByteSize() {
        return byteSize;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public String getRawSha256() {
        return rawSha256;
    }

    public String getImageFingerprint() {
        return imageFingerprint;
    }

    public int getFingerprintVersion() {
        return fingerprintVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
