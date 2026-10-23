-- Контекст tournaments: окна голосования, участники окон, голоса
-- (разделы 7, 9, 11). Enum → VARCHAR + CHECK; маппинг явный.
-- version (optimistic locking) у конкурентного voting_window.
-- PROMOTED (глобальная квалификация) добавит итерация 7 расширением CHECK.
CREATE TABLE voting_window (
    id            UUID PRIMARY KEY,
    tournament_id UUID        NOT NULL REFERENCES tournament (id),
    sequence      INTEGER     NOT NULL CHECK (sequence >= 1),
    status        VARCHAR(10) NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    opens_at      TIMESTAMPTZ NOT NULL,
    closes_at     TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT voting_window_sequence_uidx UNIQUE (tournament_id, sequence),
    CONSTRAINT voting_window_interval_chk CHECK (opens_at < closes_at)
);
-- не более одного OPEN-окна на турнир (следующее создаётся закрытием предыдущего)
CREATE UNIQUE INDEX voting_window_one_open_uidx ON voting_window (tournament_id) WHERE status = 'OPEN';
CREATE INDEX voting_window_due_idx ON voting_window (status, closes_at);

CREATE TABLE window_participant (
    id        UUID         PRIMARY KEY,
    window_id UUID         NOT NULL REFERENCES voting_window (id),
    entry_id  UUID         NOT NULL,
    user_id   UUID         NOT NULL,
    score     BIGINT       NOT NULL DEFAULT 0,
    result    VARCHAR(15)  NOT NULL CHECK (result IN ('ACTIVE', 'SURVIVED', 'ELIMINATED', 'WINNER')),
    joined_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT window_participant_pair_uidx UNIQUE (window_id, entry_id)
);
CREATE INDEX window_participant_score_idx ON window_participant (window_id, score DESC);

CREATE TABLE vote (
    id                   UUID         PRIMARY KEY,
    window_participant_id UUID        NOT NULL REFERENCES window_participant (id),
    subject_key          VARCHAR(80)  NOT NULL,
    value                VARCHAR(10)  NOT NULL CHECK (value IN ('LIKE', 'DISLIKE')),
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT vote_subject_uidx UNIQUE (window_participant_id, subject_key)
);
CREATE INDEX vote_participant_idx ON vote (window_participant_id);
