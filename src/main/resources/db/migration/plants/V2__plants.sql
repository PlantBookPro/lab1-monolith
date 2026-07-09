-- Контекст plants: растения, запреты изображений, резервы (разделы 5, 6 и 11).
-- Enum → VARCHAR + CHECK. Plant мутирует (модерация/жизнь/архив) — version
-- (optimistic locking); запреты append-only и резервы с единственным переходом
-- ACTIVE→RELEASED — без version. asset_id/owner_id/plant_id без FK:
-- контексты выделяются в сервисы (раздел 10.4), cross-schema FK связал бы их навсегда.
CREATE TABLE plant (
    id                  UUID PRIMARY KEY,
    owner_id            UUID         NOT NULL,
    asset_id            UUID         NOT NULL,
    fingerprint         VARCHAR(64)  NOT NULL,
    fingerprint_version INTEGER      NOT NULL,
    title               VARCHAR(100) NOT NULL,
    moderation_status   VARCHAR(10)  NOT NULL CHECK (moderation_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    moderation_reason   VARCHAR(255),
    life_status         VARCHAR(10)  NOT NULL CHECK (life_status IN ('ALIVE', 'DEAD')),
    died_at             TIMESTAMPTZ,
    archived_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0
);

-- Один файл — одно неархивированное растение (ADR-008): частичный уникальный
-- индекс; архивация освобождает файл для нового растения.
CREATE UNIQUE INDEX plant_active_asset_uidx ON plant (asset_id) WHERE archived_at IS NULL;
CREATE INDEX plant_owner_idx ON plant (owner_id, created_at);

CREATE TABLE image_restriction (
    id                  UUID PRIMARY KEY,
    owner_id            UUID         NOT NULL,
    fingerprint         VARCHAR(64)  NOT NULL,
    fingerprint_version INTEGER      NOT NULL,
    kind                VARCHAR(10)  NOT NULL CHECK (kind IN ('PERMANENT', 'COOLDOWN')),
    expires_at          TIMESTAMPTZ, -- NULL только для PERMANENT
    reason              VARCHAR(255) NOT NULL,
    source_entry_id     UUID,
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT image_restriction_kind_expires_chk CHECK (
        (kind = 'PERMANENT' AND expires_at IS NULL)
        OR (kind = 'COOLDOWN' AND expires_at IS NOT NULL)
    )
);
CREATE INDEX image_restriction_owner_fingerprint_idx
    ON image_restriction (owner_id, fingerprint);

CREATE TABLE plant_reservation (
    id                  UUID PRIMARY KEY,
    owner_id            UUID         NOT NULL,
    plant_id            UUID         NOT NULL,
    fingerprint         VARCHAR(64)  NOT NULL,
    fingerprint_version INTEGER      NOT NULL,
    idempotency_key     UUID         NOT NULL UNIQUE,
    status              VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'RELEASED')),
    created_at          TIMESTAMPTZ  NOT NULL,
    released_at         TIMESTAMPTZ,
    CONSTRAINT plant_reservation_status_released_chk CHECK (
        (status = 'ACTIVE' AND released_at IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL)
    )
);
-- Допущение 4: не более одного активного резерва на пару (owner_id, fingerprint).
CREATE UNIQUE INDEX plant_reservation_active_pair_uidx
    ON plant_reservation (owner_id, fingerprint) WHERE status = 'ACTIVE';
