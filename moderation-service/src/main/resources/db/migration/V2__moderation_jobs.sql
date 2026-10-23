-- Задания автоматической модерации (контекст moderation, ADR-009).
-- plant_id — ключ команды plants без FK (раздел 10.4: границы контекстов).
CREATE TABLE moderation_job (
    id              UUID PRIMARY KEY,
    plant_id        UUID        NOT NULL,
    asset_id        UUID        NOT NULL,
    status          VARCHAR(13) NOT NULL CHECK (status IN ('NEW','IN_PROGRESS','RETRY','DONE')),
    attempts        INTEGER     NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    model_version   VARCHAR(50),
    confidence      REAL,
    reason_code     VARCHAR(20) CHECK (reason_code IN ('PLANT_DETECTED','NOT_A_PLANT','STALE')),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL,
    version         BIGINT      NOT NULL DEFAULT 0
);

-- Due-опрос: только NEW/RETRY, готовые к попытке (частичный индекс)
CREATE INDEX moderation_job_due_idx
    ON moderation_job (next_attempt_at) WHERE status IN ('NEW','RETRY');

-- Последнее задание по заявке (идемпотентность слушателя)
CREATE INDEX moderation_job_plant_idx ON moderation_job (plant_id, created_at);
