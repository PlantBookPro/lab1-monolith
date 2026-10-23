-- Контекст media: неизменяемые загруженные файлы (разделы 6 и 11 требований).
-- Enum → VARCHAR + CHECK; агрегат неизменяем — version (optimistic locking) не нужен.
-- Индексы: по отпечатку (запреты повторного использования, итерация 3)
-- и по владельцу со временем загрузки.
CREATE TABLE media_asset (
    id                  UUID PRIMARY KEY,
    owner_id            UUID         NOT NULL,
    storage_key         VARCHAR(255) NOT NULL UNIQUE,
    mime_type           VARCHAR(20)  NOT NULL CHECK (mime_type IN ('image/jpeg', 'image/png')),
    byte_size           BIGINT       NOT NULL CHECK (byte_size BETWEEN 1 AND 10485760),
    width               INTEGER      NOT NULL CHECK (width > 0),
    height              INTEGER      NOT NULL CHECK (height > 0),
    raw_sha256          VARCHAR(64)  NOT NULL,
    image_fingerprint   VARCHAR(64)  NOT NULL,
    fingerprint_version INTEGER      NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL
);

CREATE INDEX media_asset_owner_idx ON media_asset (owner_id, created_at);
CREATE INDEX media_asset_fingerprint_idx ON media_asset (image_fingerprint);
