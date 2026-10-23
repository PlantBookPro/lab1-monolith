-- Контекст tournaments: глобальный турнир (раздел 8, 11): scope окон,
-- эпохи отбора, глобальные статусы участий. Enum → VARCHAR + CHECK.
-- Глобальный турнир — единственная запись типа GLOBAL с фиксированным id
-- (создаётся bootstrap-раннером, не миграцией).

ALTER TABLE tournament DROP CONSTRAINT tournament_type_check;
ALTER TABLE tournament ADD CONSTRAINT tournament_type_check
    CHECK (type IN ('PRIVATE', 'GLOBAL'));

-- участия: глобальный жизненный цикл (раздел 11); FINAL_PENDING длиннее 10
ALTER TABLE tournament_entry ALTER COLUMN status TYPE VARCHAR(15);
ALTER TABLE tournament_entry DROP CONSTRAINT tournament_entry_status_check;
ALTER TABLE tournament_entry ADD CONSTRAINT tournament_entry_status_check
    CHECK (status IN ('ACTIVE', 'ELIMINATED', 'WINNER', 'QUEUED', 'QUALIFYING',
                      'FINAL_PENDING', 'FINALIST', 'WITHDRAWN'));
-- уникальность private-пары остаётся частичной по private-статусам:
-- история глобальных участий одного пользователя не конфликтует
ALTER TABLE tournament_entry DROP CONSTRAINT tournament_entry_pair_uidx;
CREATE UNIQUE INDEX tournament_entry_pair_uidx ON tournament_entry (tournament_id, user_id)
    WHERE status IN ('ACTIVE', 'ELIMINATED', 'WINNER');
-- одно активное глобальное участие на пользователя (допущение 5)
CREATE UNIQUE INDEX tournament_entry_one_active_global_uidx ON tournament_entry (user_id)
    WHERE tournament_id = '00000007-10ba-4000-8000-000000000001'::uuid
      AND status IN ('QUEUED', 'QUALIFYING', 'FINAL_PENDING', 'FINALIST');
CREATE INDEX tournament_entry_global_status_idx ON tournament_entry (tournament_id, status);

-- окна: scope + связь с эпохой/кластером (раздел 11)
ALTER TABLE voting_window ADD COLUMN scope VARCHAR(13) NOT NULL DEFAULT 'PRIVATE'
    CHECK (scope IN ('PRIVATE', 'QUALIFICATION', 'FINAL'));
ALTER TABLE voting_window ADD COLUMN epoch_id UUID;
ALTER TABLE voting_window ADD COLUMN cluster_id UUID;
ALTER TABLE voting_window ADD COLUMN cluster_key VARCHAR(12);
ALTER TABLE voting_window DROP CONSTRAINT voting_window_sequence_uidx;
-- PRIVATE/FINAL: последовательность уникальна в своём scope
CREATE UNIQUE INDEX voting_window_private_seq_uidx ON voting_window (tournament_id, sequence)
    WHERE scope = 'PRIVATE';
CREATE UNIQUE INDEX voting_window_final_seq_uidx ON voting_window (tournament_id, sequence)
    WHERE scope = 'FINAL';
-- QUALIFICATION: одно окно на кластер эпохи
CREATE UNIQUE INDEX voting_window_qualification_cell_uidx ON voting_window (epoch_id, cluster_id)
    WHERE scope = 'QUALIFICATION';
DROP INDEX voting_window_one_open_uidx;
-- «одно открытое окно» — только для PRIVATE и FINAL (квалификационных окон
-- открытой эпохи много — по одному на кластер)
CREATE UNIQUE INDEX voting_window_one_open_private_uidx ON voting_window (tournament_id)
    WHERE status = 'OPEN' AND scope = 'PRIVATE';
CREATE UNIQUE INDEX voting_window_one_open_final_uidx ON voting_window (tournament_id)
    WHERE status = 'OPEN' AND scope = 'FINAL';
CREATE INDEX voting_window_scope_due_idx ON voting_window (scope, status, closes_at);

-- итог участника окна: PROMOTED — top-1 квалификации (раздел 11)
ALTER TABLE window_participant DROP CONSTRAINT window_participant_result_check;
ALTER TABLE window_participant ADD CONSTRAINT window_participant_result_check
    CHECK (result IN ('ACTIVE', 'SURVIVED', 'ELIMINATED', 'WINNER', 'PROMOTED'));

-- эпохи отбора (раздел 8, 11): явная запись для восстановления границ
-- и состава после рестарта
CREATE TABLE qualification_epoch (
    id            UUID PRIMARY KEY,
    tournament_id UUID        NOT NULL REFERENCES tournament (id),
    sequence      INTEGER     NOT NULL CHECK (sequence >= 1),
    status        VARCHAR(10) NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    opens_at      TIMESTAMPTZ NOT NULL,
    closes_at     TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT qualification_epoch_interval_chk CHECK (opens_at < closes_at)
);
CREATE UNIQUE INDEX qualification_epoch_seq_uidx ON qualification_epoch (tournament_id, sequence);
CREATE UNIQUE INDEX qualification_epoch_one_open_uidx ON qualification_epoch (tournament_id)
    WHERE status = 'OPEN';
