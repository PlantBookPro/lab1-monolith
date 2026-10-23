-- Контекст geo: снимки кластеров эпох (раздел 8, 11). Состав и версия
-- политики неизменны после фиксации. entry_id — логический UUID (межсхемных
-- FK нет, раздел 11).
CREATE TABLE cluster_snapshot (
    id             UUID         PRIMARY KEY,
    epoch_id       UUID         NOT NULL,
    cluster_key    VARCHAR(12)  NOT NULL,
    policy_version VARCHAR(30)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX cluster_snapshot_epoch_idx ON cluster_snapshot (epoch_id);

CREATE TABLE cluster_member (
    snapshot_id      UUID    NOT NULL REFERENCES cluster_snapshot (id),
    user_id          UUID    NOT NULL,
    entry_id         UUID    NOT NULL,
    location_version BIGINT  NOT NULL,
    PRIMARY KEY (snapshot_id, entry_id)
);
