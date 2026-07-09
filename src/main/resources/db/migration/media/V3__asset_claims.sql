-- Контекст media: задействованность файлов растениями (ADR-008, итерация 3).
-- Задействованность живёт в media (upstream), потому что media не может
-- зависеть от plants (раздел 4.3, иначе цикл): plants командует через
-- MediaAssetClaims, media хранит. Один файл — одно растение: PK по asset_id.
-- Инвариант «одно неархивированное растение на файл» дублируется на стороне
-- plants частичным уникальным индексом plant_active_asset_uidx (V2 plants).
-- Запись перезаписывается upsert-ом фасада — version (optimistic locking) не нужен.
-- FK ON DELETE CASCADE: удаление asset'а убирает и задействованность.
CREATE TABLE asset_claim (
    asset_id         UUID PRIMARY KEY REFERENCES media_asset (id) ON DELETE CASCADE,
    plant_id         UUID        NOT NULL,
    publicly_visible BOOLEAN     NOT NULL,
    claimed_at       TIMESTAMPTZ NOT NULL
);
